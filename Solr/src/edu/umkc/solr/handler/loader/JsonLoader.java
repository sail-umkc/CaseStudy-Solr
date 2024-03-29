package edu.umkc.solr.handler.loader;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.JsonRecordReader;
import org.noggit.JSONParser;
import org.noggit.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umkc.solr.handler.RequestHandlerUtils;
import edu.umkc.solr.handler.UpdateRequestHandler;
import edu.umkc.solr.request.SolrQueryRequest;
import edu.umkc.solr.response.SolrQueryResponse;
import edu.umkc.solr.schema.SchemaField;
import edu.umkc.solr.update.AddUpdateCommand;
import edu.umkc.solr.update.CommitUpdateCommand;
import edu.umkc.solr.update.DeleteUpdateCommand;
import edu.umkc.solr.update.RollbackUpdateCommand;
import edu.umkc.solr.update.processor.UpdateRequestProcessor;
import edu.umkc.solr.util.RecordingJSONParser;
import static org.apache.solr.common.params.CommonParams.JSON;
import static org.apache.solr.common.params.CommonParams.PATH;


/**
 * @since solr 4.0
 */
public class JsonLoader extends ContentStreamLoader {
  final static Logger log = LoggerFactory.getLogger(JsonLoader.class);
  private static final String CHILD_DOC_KEY = "_childDocuments_";

  @Override
  public String getDefaultWT() {
    return JSON;
  }

  @Override
  public void load(SolrQueryRequest req, SolrQueryResponse rsp,
                   ContentStream stream, UpdateRequestProcessor processor) throws Exception {
    new SingleThreadedJsonLoader(req, rsp, processor).load(req, rsp, stream, processor);
  }


  static class SingleThreadedJsonLoader extends ContentStreamLoader {

    protected final UpdateRequestProcessor processor;
    protected final SolrQueryRequest req;
    protected SolrQueryResponse rsp;
    protected JSONParser parser;
    protected final int commitWithin;
    protected final boolean overwrite;

    public SingleThreadedJsonLoader(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor processor) {
      this.processor = processor;
      this.req = req;
      this.rsp = rsp;

      commitWithin = req.getParams().getInt(UpdateParams.COMMIT_WITHIN, -1);
      overwrite = req.getParams().getBool(UpdateParams.OVERWRITE, true);
    }

    @Override
    public void load(SolrQueryRequest req,
                     SolrQueryResponse rsp,
                     ContentStream stream,
                     UpdateRequestProcessor processor) throws Exception {

      Reader reader = null;
      try {
        reader = stream.getReader();
        if (log.isTraceEnabled()) {
          String body = IOUtils.toString(reader);
          log.trace("body", body);
          reader = new StringReader(body);
        }

        this.processUpdate(reader);
      } finally {
        IOUtils.closeQuietly(reader);
      }
    }

    @SuppressWarnings("fallthrough")
    void processUpdate(Reader reader) throws IOException {
      String path = (String) req.getContext().get(PATH);
      if (UpdateRequestHandler.DOC_PATH.equals(path) || "false".equals(req.getParams().get("json.command"))) {
        String split = req.getParams().get("split");
        String[] f = req.getParams().getParams("f");
        handleSplitMode(split, f, reader);
        return;
      }
      parser = new JSONParser(reader);
      int ev = parser.nextEvent();
      while (ev != JSONParser.EOF) {

        switch (ev) {
          case JSONParser.ARRAY_START:
            handleAdds();
            break;

          case JSONParser.STRING:
            if (parser.wasKey()) {
              String v = parser.getString();
              if (v.equals(UpdateRequestHandler.ADD)) {
                int ev2 = parser.nextEvent();
                if (ev2 == JSONParser.OBJECT_START) {
                  processor.processAdd(parseAdd());
                } else if (ev2 == JSONParser.ARRAY_START) {
                  handleAdds();
                } else {
                  assertEvent(ev2, JSONParser.OBJECT_START);
                }
              } else if (v.equals(UpdateRequestHandler.COMMIT)) {
                CommitUpdateCommand cmd = new CommitUpdateCommand(req, false);
                cmd.waitSearcher = true;
                parseCommitOptions(cmd);
                processor.processCommit(cmd);
              } else if (v.equals(UpdateRequestHandler.OPTIMIZE)) {
                CommitUpdateCommand cmd = new CommitUpdateCommand(req, true);
                cmd.waitSearcher = true;
                parseCommitOptions(cmd);
                processor.processCommit(cmd);
              } else if (v.equals(UpdateRequestHandler.DELETE)) {
                handleDeleteCommand();
              } else if (v.equals(UpdateRequestHandler.ROLLBACK)) {
                processor.processRollback(parseRollback());
              } else {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown command '" + v + "' at [" + parser.getPosition() + "]");
              }
              break;
            }
            // fall through

          case JSONParser.LONG:
          case JSONParser.NUMBER:
          case JSONParser.BIGNUMBER:
          case JSONParser.BOOLEAN:
          case JSONParser.NULL:
            log.info("Can't have a value here. Unexpected "
                + JSONParser.getEventString(ev) + " at [" + parser.getPosition() + "]");

          case JSONParser.OBJECT_START:
          case JSONParser.OBJECT_END:
          case JSONParser.ARRAY_END:
            break;

          default:
            log.info("Noggit UNKNOWN_EVENT_ID: " + ev);
            break;
        }
        // read the next event
        ev = parser.nextEvent();
      }
    }

    private void handleSplitMode(String split, String[] fields, final Reader reader) throws IOException {
      if (split == null) split = "/";
      if (fields == null || fields.length == 0) fields = new String[]{"$FQN:/**"};
      final boolean echo = "true".equals(req.getParams().get("echo"));
      final String srcField = req.getParams().get("srcField");
      final boolean mapUniqueKeyOnly = req.getParams().getBool("mapUniqueKeyOnly", false);
      if (srcField != null) {
        if (!"/".equals(split))
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Raw data can be stored only if split=/");
        parser = new RecordingJSONParser(reader);
      } else {
        parser = new JSONParser(reader);

      }

      JsonRecordReader jsonRecordReader = JsonRecordReader.getInst(split, Arrays.asList(fields));
      jsonRecordReader.streamRecords(parser, new JsonRecordReader.Handler() {
        ArrayList docs = null;

        @Override
        public void handle(Map<String, Object> record, String path) {
          Map<String, Object> copy = getDocMap(record, parser, srcField, mapUniqueKeyOnly);

          if (echo) {
            if (docs == null) {
              docs = new ArrayList();
              rsp.add("docs", docs);
            }
            docs.add(copy);
          } else {
            AddUpdateCommand cmd = new AddUpdateCommand(req);
            cmd.commitWithin = commitWithin;
            cmd.overwrite = overwrite;
            cmd.solrDoc = new SolrInputDocument();
            for (Map.Entry<String, Object> entry : copy.entrySet()) {
              cmd.solrDoc.setField(entry.getKey(), entry.getValue());
            }
            try {
              processor.processAdd(cmd);
            } catch (IOException e) {
              throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Error inserting document: ", e);
            }
          }
        }
      });
    }

    private Map<String, Object> getDocMap(Map<String, Object> record, JSONParser parser, String srcField, boolean mapUniqueKeyOnly) {
      Map result = record;
      if (srcField != null && parser instanceof RecordingJSONParser) {
        //if srcFIeld specified extract it out first
        result = new LinkedHashMap(record);
        RecordingJSONParser rjp = (RecordingJSONParser) parser;
        result.put(srcField, rjp.getBuf());
        rjp.resetBuf();
      }
      if (mapUniqueKeyOnly) {
        SchemaField sf = req.getSchema().getUniqueKeyField();
        if (sf == null)
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No uniqueKey specified in schema");
        String df = req.getParams().get(CommonParams.DF);
        if (df == null) throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No 'df' specified in request");
        Map copy = new LinkedHashMap();
        String uniqueField = (String) record.get(sf.getName());
        if (uniqueField == null) uniqueField = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        copy.put(sf.getName(), uniqueField);
        if (srcField != null && result.containsKey(srcField)) {
          copy.put(srcField, result.remove(srcField));
        }
        copy.put(df, result.values());
        result = copy;
      }


      return result;
    }


    //
    // "delete":"id"
    // "delete":["id1","id2"]
    // "delete":{"id":"foo"}
    // "delete":{"query":"myquery"}
    //
    void handleDeleteCommand() throws IOException {
      int ev = parser.nextEvent();
      switch (ev) {
        case JSONParser.ARRAY_START:
          handleDeleteArray(ev);
          break;
        case JSONParser.OBJECT_START:
          handleDeleteMap(ev);
          break;
        default:
          handleSingleDelete(ev);
      }
    }

    // returns the string value for a primitive value, or null for the null value
    String getString(int ev) throws IOException {
      switch (ev) {
        case JSONParser.STRING:
          return parser.getString();
        case JSONParser.BIGNUMBER:
        case JSONParser.NUMBER:
        case JSONParser.LONG:
          return parser.getNumberChars().toString();
        case JSONParser.BOOLEAN:
          return Boolean.toString(parser.getBoolean());
        case JSONParser.NULL:
          return null;
        default:
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "Expected primitive JSON value but got: " + JSONParser.getEventString(ev)
                  + " at [" + parser.getPosition() + "]");
      }
    }


    void handleSingleDelete(int ev) throws IOException {
      if (ev == JSONParser.OBJECT_START) {
        handleDeleteMap(ev);
      } else {
        DeleteUpdateCommand cmd = new DeleteUpdateCommand(req);
        cmd.commitWithin = commitWithin;
        String id = getString(ev);
        cmd.setId(id);
        processor.processDelete(cmd);
      }
    }

    void handleDeleteArray(int ev) throws IOException {
      assert ev == JSONParser.ARRAY_START;
      for (; ; ) {
        ev = parser.nextEvent();
        if (ev == JSONParser.ARRAY_END) return;
        handleSingleDelete(ev);
      }
    }

    void handleDeleteMap(int ev) throws IOException {
      assert ev == JSONParser.OBJECT_START;

      DeleteUpdateCommand cmd = new DeleteUpdateCommand(req);
      cmd.commitWithin = commitWithin;

      while (true) {
        ev = parser.nextEvent();
        if (ev == JSONParser.STRING) {
          String key = parser.getString();
          if (parser.wasKey()) {
            if ("id".equals(key)) {
              cmd.setId(getString(parser.nextEvent()));
            } else if ("query".equals(key)) {
              cmd.setQuery(parser.getString());
            } else if ("commitWithin".equals(key)) {
              cmd.commitWithin = (int) parser.getLong();
            } else if ("_version_".equals(key)) {
              cmd.setVersion(parser.getLong());
            } else if ("_route_".equals(key)) {
              cmd.setRoute(parser.getString());
            } else {
              throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown key '" + key + "' at [" + parser.getPosition() + "]");
            }
          } else {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                "invalid string: " + key
                    + " at [" + parser.getPosition() + "]");
          }
        } else if (ev == JSONParser.OBJECT_END) {
          if (cmd.getId() == null && cmd.getQuery() == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Missing id or query for delete at [" + parser.getPosition() + "]");
          }

          processor.processDelete(cmd);
          return;
        } else {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "Got: " + JSONParser.getEventString(ev)
                  + " at [" + parser.getPosition() + "]");
        }
      }
    }


    RollbackUpdateCommand parseRollback() throws IOException {
      assertNextEvent(JSONParser.OBJECT_START);
      assertNextEvent(JSONParser.OBJECT_END);
      return new RollbackUpdateCommand(req);
    }

    void parseCommitOptions(CommitUpdateCommand cmd) throws IOException {
      assertNextEvent(JSONParser.OBJECT_START);
      final Map<String, Object> map = (Map) ObjectBuilder.getVal(parser);

      // SolrParams currently expects string values...
      SolrParams p = new SolrParams() {
        @Override
        public String get(String param) {
          Object o = map.get(param);
          return o == null ? null : o.toString();
        }

        @Override
        public String[] getParams(String param) {
          return new String[]{get(param)};
        }

        @Override
        public Iterator<String> getParameterNamesIterator() {
          return map.keySet().iterator();
        }
      };

      RequestHandlerUtils.validateCommitParams(p);
      p = SolrParams.wrapDefaults(p, req.getParams());   // default to the normal request params for commit options
      RequestHandlerUtils.updateCommit(cmd, p);
    }

    AddUpdateCommand parseAdd() throws IOException {
      AddUpdateCommand cmd = new AddUpdateCommand(req);
      cmd.commitWithin = commitWithin;
      cmd.overwrite = overwrite;

      float boost = 1.0f;

      while (true) {
        int ev = parser.nextEvent();
        if (ev == JSONParser.STRING) {
          if (parser.wasKey()) {
            String key = parser.getString();
            if ("doc".equals(key)) {
              if (cmd.solrDoc != null) {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Multiple documents in same"
                    + " add command at [" + parser.getPosition() + "]");
              }
              ev = assertNextEvent(JSONParser.OBJECT_START);
              cmd.solrDoc = parseDoc(ev);
            } else if (UpdateRequestHandler.OVERWRITE.equals(key)) {
              cmd.overwrite = parser.getBoolean(); // reads next boolean
            } else if (UpdateRequestHandler.COMMIT_WITHIN.equals(key)) {
              cmd.commitWithin = (int) parser.getLong();
            } else if ("boost".equals(key)) {
              boost = Float.parseFloat(parser.getNumberChars().toString());
            } else {
              throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown key '" + key + "' at [" + parser.getPosition() + "]");
            }
          } else {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                "Should be a key "
                    + " at [" + parser.getPosition() + "]");
          }
        } else if (ev == JSONParser.OBJECT_END) {
          if (cmd.solrDoc == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Missing solr document at [" + parser.getPosition() + "]");
          }
          cmd.solrDoc.setDocumentBoost(boost);
          return cmd;
        } else {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "Got: " + JSONParser.getEventString(ev)
                  + " at [" + parser.getPosition() + "]");
        }
      }
    }


    void handleAdds() throws IOException {
      while (true) {
        AddUpdateCommand cmd = new AddUpdateCommand(req);
        cmd.commitWithin = commitWithin;
        cmd.overwrite = overwrite;

        int ev = parser.nextEvent();
        if (ev == JSONParser.ARRAY_END) break;

        assertEvent(ev, JSONParser.OBJECT_START);
        cmd.solrDoc = parseDoc(ev);
        processor.processAdd(cmd);
      }
    }


    int assertNextEvent(int expected) throws IOException {
      int got = parser.nextEvent();
      assertEvent(got, expected);
      return got;
    }

    void assertEvent(int ev, int expected) {
      if (ev != expected) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "Expected: " + JSONParser.getEventString(expected)
                + " but got " + JSONParser.getEventString(ev)
                + " at [" + parser.getPosition() + "]");
      }
    }


    private SolrInputDocument parseDoc(int ev) throws IOException {
      assert ev == JSONParser.OBJECT_START;

      SolrInputDocument sdoc = new SolrInputDocument();
      for (; ; ) {
        ev = parser.nextEvent();
        if (ev == JSONParser.OBJECT_END) {
          return sdoc;
        }
        String fieldName = parser.getString();

        if (fieldName.equals(JsonLoader.CHILD_DOC_KEY)) {
          ev = parser.nextEvent();
          assertEvent(ev, JSONParser.ARRAY_START);
          while ((ev = parser.nextEvent()) != JSONParser.ARRAY_END) {
            assertEvent(ev, JSONParser.OBJECT_START);

            sdoc.addChildDocument(parseDoc(ev));
          }
        } else {
          SolrInputField sif = new SolrInputField(fieldName);
          parseFieldValue(sif);
          // pulling out the pieces may seem weird, but it's because
          // SolrInputDocument.addField will do the right thing
          // if the doc already has another value for this field
          // (ie: repeating fieldname keys)
          sdoc.addField(sif.getName(), sif.getValue(), sif.getBoost());
        }

      }
    }

    private void parseFieldValue(SolrInputField sif) throws IOException {
      int ev = parser.nextEvent();
      if (ev == JSONParser.OBJECT_START) {
        parseExtendedFieldValue(sif, ev);
      } else {
        Object val = parseNormalFieldValue(ev, sif.getName());
        sif.setValue(val, 1.0f);
      }
    }

    private void parseExtendedFieldValue(SolrInputField sif, int ev) throws IOException {
      assert ev == JSONParser.OBJECT_START;

      float boost = 1.0f;
      Object normalFieldValue = null;
      Map<String, Object> extendedInfo = null;

      for (; ; ) {
        ev = parser.nextEvent();
        switch (ev) {
          case JSONParser.STRING:
            String label = parser.getString();
            if ("boost".equals(label)) {
              ev = parser.nextEvent();
              if (ev != JSONParser.NUMBER &&
                  ev != JSONParser.LONG &&
                  ev != JSONParser.BIGNUMBER) {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Boost should have number. "
                    + "Unexpected " + JSONParser.getEventString(ev) + " at [" + parser.getPosition() + "], field=" + sif.getName());
              }

              boost = (float) parser.getDouble();
            } else if ("value".equals(label)) {
              normalFieldValue = parseNormalFieldValue(parser.nextEvent(), sif.getName());
            } else {
              // If we encounter other unknown map keys, then use a map
              if (extendedInfo == null) {
                extendedInfo = new HashMap<>(2);
              }
              // for now, the only extended info will be field values
              // we could either store this as an Object or a SolrInputField
              Object val = parseNormalFieldValue(parser.nextEvent(), sif.getName());
              extendedInfo.put(label, val);
            }
            break;

          case JSONParser.OBJECT_END:
            if (extendedInfo != null) {
              if (normalFieldValue != null) {
                extendedInfo.put("value", normalFieldValue);
              }
              sif.setValue(extendedInfo, boost);
            } else {
              sif.setValue(normalFieldValue, boost);
            }
            return;

          default:
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Error parsing JSON extended field value. "
                + "Unexpected " + JSONParser.getEventString(ev) + " at [" + parser.getPosition() + "], field=" + sif.getName());
        }
      }
    }


    private Object parseNormalFieldValue(int ev, String fieldName) throws IOException {
      if (ev == JSONParser.ARRAY_START) {
        List<Object> val = parseArrayFieldValue(ev, fieldName);
        return val;
      } else {
        Object val = parseSingleFieldValue(ev, fieldName);
        return val;
      }
    }


    private Object parseSingleFieldValue(int ev, String fieldName) throws IOException {
      switch (ev) {
        case JSONParser.STRING:
          return parser.getString();
        case JSONParser.LONG:
          return parser.getLong();
        case JSONParser.NUMBER:
          return parser.getDouble();
        case JSONParser.BIGNUMBER:
          return parser.getNumberChars().toString();
        case JSONParser.BOOLEAN:
          return parser.getBoolean();
        case JSONParser.NULL:
          parser.getNull();
          return null;
        case JSONParser.ARRAY_START:
          return parseArrayFieldValue(ev, fieldName);
        default:
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Error parsing JSON field value. "
              + "Unexpected " + JSONParser.getEventString(ev) + " at [" + parser.getPosition() + "], field=" + fieldName);
      }
    }


    private List<Object> parseArrayFieldValue(int ev, String fieldName) throws IOException {
      assert ev == JSONParser.ARRAY_START;

      ArrayList lst = new ArrayList(2);
      for (; ; ) {
        ev = parser.nextEvent();
        if (ev == JSONParser.ARRAY_END) {
          return lst;
        }
        Object val = parseSingleFieldValue(ev, fieldName);
        lst.add(val);
      }
    }
  }

}
