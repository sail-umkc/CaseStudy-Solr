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

package edu.umkc.solr.core;


import com.google.common.base.Charsets;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;

import edu.umkc.solr.cloud.ZkSolrResourceLoader;
import edu.umkc.solr.handler.component.SearchComponent;
import edu.umkc.solr.request.SolrRequestHandler;
import edu.umkc.solr.response.QueryResponseWriter;
import edu.umkc.solr.response.transform.TransformerFactory;
import edu.umkc.solr.rest.RestManager;
import edu.umkc.solr.schema.IndexSchemaFactory;
import edu.umkc.solr.search.CacheConfig;
import edu.umkc.solr.search.FastLRUCache;
import edu.umkc.solr.search.QParserPlugin;
import edu.umkc.solr.search.ValueSourceParser;
import edu.umkc.solr.search.stats.StatsCache;
import edu.umkc.solr.servlet.SolrRequestParsers;
import edu.umkc.solr.spelling.QueryConverter;
import edu.umkc.solr.update.SolrIndexConfig;
import edu.umkc.solr.update.UpdateLog;
import edu.umkc.solr.update.processor.UpdateRequestProcessorChain;
import edu.umkc.solr.update.processor.UpdateRequestProcessorFactory;
import edu.umkc.solr.util.DOMUtil;
import edu.umkc.solr.util.FileUtils;
import edu.umkc.solr.util.RegexFileFilter;
import edu.umkc.type.ISolrResourceLoader;
import comp.SolrIndexSearcherFactory.SolrIndexSearcherFactoryImp.SolrIndexSearcher;
import comp.SolrResourceLoader.SolrResourceLoaderImp;

import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.util.Version;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.util.IOUtils;
import org.noggit.JSONParser;
import org.noggit.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static edu.umkc.solr.core.ConfigOverlay.ZNODEVER;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.LAZY;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.MULTI_OK;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.NOOP;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.REQUIRE_CLASS;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME;
import static edu.umkc.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME_IN_OVERLAY;
import static org.apache.solr.common.cloud.ZkNodeProps.makeMap;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.CommonParams.PATH;


/**
 * Provides a static reference to a Config object modeling the main
 * configuration data for a a Solr instance -- typically found in
 * "solrconfig.xml".
 */
public class SolrConfig extends Config implements MapSerializable {

  public static final Logger log = LoggerFactory.getLogger(SolrConfig.class);

  public static final String DEFAULT_CONF_FILE = "solrconfig.xml";
  private RequestParams requestParams;

  public static enum PluginOpts {
    MULTI_OK,
    REQUIRE_NAME,
    REQUIRE_NAME_IN_OVERLAY,
    REQUIRE_CLASS,
    LAZY,
    // EnumSet.of and/or EnumSet.copyOf(Collection) are anoying
    // because of type determination
    NOOP
  }

  private int multipartUploadLimitKB;

  private int formUploadLimitKB;

  private boolean enableRemoteStreams;

  private boolean handleSelect;

  private boolean addHttpRequestToContext;

  private final SolrRequestParsers solrRequestParsers;

  /**
   * Creates a default instance from the solrconfig.xml.
   */
  public SolrConfig()
      throws ParserConfigurationException, IOException, SAXException {
    this((SolrResourceLoaderImp) null, DEFAULT_CONF_FILE, null);
  }

  /**
   * Creates a configuration instance from a configuration name.
   * A default resource loader will be created (@see SolrResourceLoader)
   *
   * @param name the configuration name used by the loader
   */
  public SolrConfig(String name)
      throws ParserConfigurationException, IOException, SAXException {
    this((SolrResourceLoaderImp) null, name, null);
  }

  /**
   * Creates a configuration instance from a configuration name and stream.
   * A default resource loader will be created (@see SolrResourceLoader).
   * If the stream is null, the resource loader will open the configuration stream.
   * If the stream is not null, no attempt to load the resource will occur (the name is not used).
   *
   * @param name the configuration name
   * @param is   the configuration stream
   */
  public SolrConfig(String name, InputSource is)
      throws ParserConfigurationException, IOException, SAXException {
    this((SolrResourceLoaderImp) null, name, is);
  }

  /**
   * Creates a configuration instance from an instance directory, configuration name and stream.
   *
   * @param instanceDir the directory used to create the resource loader
   * @param name        the configuration name used by the loader if the stream is null
   * @param is          the configuration stream
   */
  public SolrConfig(String instanceDir, String name, InputSource is)
      throws ParserConfigurationException, IOException, SAXException {
    this(new SolrResourceLoaderImp(instanceDir), name, is);
  }

  public static SolrConfig readFromResourceLoader(ISolrResourceLoader loader, String name) {
    try {
      return new SolrConfig(loader, name, null);
    } catch (Exception e) {
      String resource;
      if (loader instanceof ZkSolrResourceLoader) {
        resource = name;
      } else {
        resource = loader.getConfigDir() + name;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error loading solr config from " + resource, e);
    }
  }

  /**
   * Creates a configuration instance from a resource loader, a configuration name and a stream.
   * If the stream is null, the resource loader will open the configuration stream.
   * If the stream is not null, no attempt to load the resource will occur (the name is not used).
   *
   * @param loader the resource loader
   * @param name   the configuration name
   * @param is     the configuration stream
   */
  public SolrConfig(ISolrResourceLoader loader, String name, InputSource is)
      throws ParserConfigurationException, IOException, SAXException {
    super(loader, name, is, "/config/");
    getOverlay();//just in case it is not initialized
    getRequestParams();
    initLibs();
    luceneMatchVersion = getLuceneVersion("luceneMatchVersion");
    String indexConfigPrefix;

    // Old indexDefaults and mainIndex sections are deprecated and fails fast for luceneMatchVersion=>LUCENE_4_0_0.
    // For older solrconfig.xml's we allow the old sections, but never mixed with the new <indexConfig>
    boolean hasDeprecatedIndexConfig = (getNode("indexDefaults", false) != null) || (getNode("mainIndex", false) != null);
    boolean hasNewIndexConfig = getNode("indexConfig", false) != null;
    if (hasDeprecatedIndexConfig) {
      if(luceneMatchVersion.onOrAfter(Version.LUCENE_4_0_0_ALPHA)) {
        throw new SolrException(ErrorCode.FORBIDDEN, "<indexDefaults> and <mainIndex> configuration sections are discontinued. Use <indexConfig> instead.");
      } else {
        // Still allow the old sections for older LuceneMatchVersion's
        if(hasNewIndexConfig) {
          throw new SolrException(ErrorCode.FORBIDDEN, "Cannot specify both <indexDefaults>, <mainIndex> and <indexConfig> at the same time. Please use <indexConfig> only.");
        }
        log.warn("<indexDefaults> and <mainIndex> configuration sections are deprecated and will fail for luceneMatchVersion=LUCENE_4_0_0 and later. Please use <indexConfig> instead.");
        defaultIndexConfig = new SolrIndexConfig(this, "indexDefaults", null);
        mainIndexConfig = new SolrIndexConfig(this, "mainIndex", defaultIndexConfig);
        indexConfigPrefix = "mainIndex";
      }
    } else {
      defaultIndexConfig = mainIndexConfig = null;
      indexConfigPrefix = "indexConfig";
    }
    assertWarnOrFail("The <nrtMode> config has been discontinued and NRT mode is always used by Solr." +
            " This config will be removed in future versions.", getNode(indexConfigPrefix + "/nrtMode", false) == null,
        false
    );

    // Parse indexConfig section, using mainIndex as backup in case old config is used
    indexConfig = new SolrIndexConfig(this, "indexConfig", mainIndexConfig);

    booleanQueryMaxClauseCount = getInt("query/maxBooleanClauses", BooleanQuery.getMaxClauseCount());
    log.info("Using Lucene MatchVersion: " + luceneMatchVersion);

    // Warn about deprecated / discontinued parameters
    // boolToFilterOptimizer has had no effect since 3.1
    if (get("query/boolTofilterOptimizer", null) != null)
      log.warn("solrconfig.xml: <boolTofilterOptimizer> is currently not implemented and has no effect.");
    if (get("query/HashDocSet", null) != null)
      log.warn("solrconfig.xml: <HashDocSet> is deprecated and no longer recommended used.");

// TODO: Old code - in case somebody wants to re-enable. Also see SolrIndexSearcher#search()
//    filtOptEnabled = getBool("query/boolTofilterOptimizer/@enabled", false);
//    filtOptCacheSize = getInt("query/boolTofilterOptimizer/@cacheSize",32);
//    filtOptThreshold = getFloat("query/boolTofilterOptimizer/@threshold",.05f);

    useFilterForSortedQuery = getBool("query/useFilterForSortedQuery", false);
    queryResultWindowSize = Math.max(1, getInt("query/queryResultWindowSize", 1));
    queryResultMaxDocsCached = getInt("query/queryResultMaxDocsCached", Integer.MAX_VALUE);
    enableLazyFieldLoading = getBool("query/enableLazyFieldLoading", false);


    filterCacheConfig = CacheConfig.getConfig(this, "query/filterCache");
    queryResultCacheConfig = CacheConfig.getConfig(this, "query/queryResultCache");
    documentCacheConfig = CacheConfig.getConfig(this, "query/documentCache");
    CacheConfig conf = CacheConfig.getConfig(this, "query/fieldValueCache");
    if (conf == null) {
      Map<String, String> args = new HashMap<>();
      args.put(NAME, "fieldValueCache");
      args.put("size", "10000");
      args.put("initialSize", "10");
      args.put("showItems", "-1");
      conf = new CacheConfig(FastLRUCache.class, args, null);
    }
    fieldValueCacheConfig = conf;
    unlockOnStartup = getBool(indexConfigPrefix + "/unlockOnStartup", false);
    useColdSearcher = getBool("query/useColdSearcher", false);
    dataDir = get("dataDir", null);
    if (dataDir != null && dataDir.length() == 0) dataDir = null;

    userCacheConfigs = CacheConfig.getMultipleConfigs(this, "query/cache");

    SolrIndexSearcher.initRegenerators(this);

    hashSetInverseLoadFactor = 1.0f / getFloat("//HashDocSet/@loadFactor", 0.75f);
    hashDocSetMaxSize = getInt("//HashDocSet/@maxSize", 3000);

    httpCachingConfig = new HttpCachingConfig(this);

    Node jmx = getNode("jmx", false);
    if (jmx != null) {
      jmxConfig = new JmxConfiguration(true,
          get("jmx/@agentId", null),
          get("jmx/@serviceUrl", null),
          get("jmx/@rootName", null));

    } else {
      jmxConfig = new JmxConfiguration(false, null, null, null);
    }
    maxWarmingSearchers = getInt("query/maxWarmingSearchers", Integer.MAX_VALUE);
    slowQueryThresholdMillis = getInt("query/slowQueryThresholdMillis", -1);
    for (SolrPluginInfo plugin : plugins) loadPluginInfo(plugin);
    updateHandlerInfo = loadUpdatehandlerInfo();

    multipartUploadLimitKB = getInt(
        "requestDispatcher/requestParsers/@multipartUploadLimitInKB", 2048);

    formUploadLimitKB = getInt(
        "requestDispatcher/requestParsers/@formdataUploadLimitInKB", 2048);

    enableRemoteStreams = getBool(
        "requestDispatcher/requestParsers/@enableRemoteStreaming", false);

    // Let this filter take care of /select?xxx format
    handleSelect = getBool(
        "requestDispatcher/@handleSelect", true);

    addHttpRequestToContext = getBool(
        "requestDispatcher/requestParsers/@addHttpRequestToContext", false);

    List<PluginInfo> argsInfos = getPluginInfos(InitParams.class.getName());
    if (argsInfos != null) {
      Map<String, InitParams> argsMap = new HashMap<>();
      for (PluginInfo p : argsInfos) {
        InitParams args = new InitParams(p);
        argsMap.put(args.name == null ? String.valueOf(args.hashCode()) : args.name, args);
      }
      this.initParams = Collections.unmodifiableMap(argsMap);

    }

    solrRequestParsers = new SolrRequestParsers(this);
    Config.log.info("Loaded SolrConfig: " + name);
  }

  public static final List<SolrPluginInfo> plugins = ImmutableList.<SolrPluginInfo>builder()
      .add(new SolrPluginInfo(SolrRequestHandler.class, SolrRequestHandler.TYPE, REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK, LAZY))
      .add(new SolrPluginInfo(QParserPlugin.class, "queryParser", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK))
      .add(new SolrPluginInfo(QueryResponseWriter.class, "queryResponseWriter", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK, LAZY))
      .add(new SolrPluginInfo(ValueSourceParser.class, "valueSourceParser", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK))
      .add(new SolrPluginInfo(TransformerFactory.class, "transformer", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK))
      .add(new SolrPluginInfo(SearchComponent.class, "searchComponent", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK))
      .add(new SolrPluginInfo(UpdateRequestProcessorFactory.class, "updateProcessor", REQUIRE_NAME, REQUIRE_CLASS, MULTI_OK))
          // TODO: WTF is up with queryConverter???
          // it aparently *only* works as a singleton? - SOLR-4304
          // and even then -- only if there is a single SpellCheckComponent
          // because of queryConverter.setIndexAnalyzer
      .add(new SolrPluginInfo(QueryConverter.class, "queryConverter", REQUIRE_NAME, REQUIRE_CLASS))
      .add(new SolrPluginInfo(PluginBag.RuntimeLib.class, "runtimeLib", REQUIRE_NAME, MULTI_OK))
          // this is hackish, since it picks up all SolrEventListeners,
          // regardless of when/how/why they are used (or even if they are
          // declared outside of the appropriate context) but there's no nice
          // way around that in the PluginInfo framework
      .add(new SolrPluginInfo(InitParams.class, InitParams.TYPE, MULTI_OK, REQUIRE_NAME_IN_OVERLAY))
      .add(new SolrPluginInfo(SolrEventListener.class, "//listener", REQUIRE_CLASS, MULTI_OK, REQUIRE_NAME_IN_OVERLAY))

      .add(new SolrPluginInfo(DirectoryFactory.class, "directoryFactory", REQUIRE_CLASS))
      .add(new SolrPluginInfo(IndexDeletionPolicy.class, "indexConfig/deletionPolicy", REQUIRE_CLASS))
      .add(new SolrPluginInfo(CodecFactory.class, "codecFactory", REQUIRE_CLASS))
      .add(new SolrPluginInfo(IndexReaderFactory.class, "indexReaderFactory", REQUIRE_CLASS))
      .add(new SolrPluginInfo(UpdateRequestProcessorChain.class, "updateRequestProcessorChain", MULTI_OK))
      .add(new SolrPluginInfo(UpdateLog.class, "updateHandler/updateLog"))
      .add(new SolrPluginInfo(IndexSchemaFactory.class, "schemaFactory", REQUIRE_CLASS))
      .add(new SolrPluginInfo(RestManager.class, "restManager"))
      .add(new SolrPluginInfo(StatsCache.class, "statsCache", REQUIRE_CLASS))
      .build();
  public static final Map<String, SolrPluginInfo> classVsSolrPluginInfo;

  static {
    Map<String, SolrPluginInfo> map = new HashMap<>();
    for (SolrPluginInfo plugin : plugins) map.put(plugin.clazz.getName(), plugin);
    classVsSolrPluginInfo = Collections.unmodifiableMap(map);
  }

  public static class SolrPluginInfo {

    public final Class clazz;
    public final String tag;
    public final Set<PluginOpts> options;


    private SolrPluginInfo(Class clz, String tag, PluginOpts... opts) {
      this.clazz = clz;
      this.tag = tag;
      this.options = opts == null ? Collections.EMPTY_SET : EnumSet.of(NOOP, opts);
    }

    public String getCleanTag() {
      return tag.replaceAll("/", "");
    }

    public String getTagCleanLower() {
      return getCleanTag().toLowerCase(Locale.ROOT);

    }
  }

  public static ConfigOverlay getConfigOverlay(ISolrResourceLoader loader) {
    InputStream in = null;
    InputStreamReader isr = null;
    try {
      try {
        in = loader.openResource(ConfigOverlay.RESOURCE_NAME);
      } catch (IOException e) {
        // TODO: we should be explicitly looking for file not found exceptions
        // and logging if it's not the expected IOException
        // hopefully no problem, assume no overlay.json file
        return new ConfigOverlay(Collections.EMPTY_MAP, -1);
      }
      
      int version = 0; // will be always 0 for file based resourceLoader
      if (in instanceof ZkSolrResourceLoader.ZkByteArrayInputStream) {
        version = ((ZkSolrResourceLoader.ZkByteArrayInputStream) in).getStat().getVersion();
        log.info("config overlay loaded . version : {} ", version);
      }
      isr = new InputStreamReader(in, StandardCharsets.UTF_8);
      Map m = (Map) ObjectBuilder.getVal(new JSONParser(isr));
      return new ConfigOverlay(m, version);
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error reading config overlay", e);
    } finally {
      IOUtils.closeQuietly(isr);
      IOUtils.closeQuietly(in);
    }
  }

  private Map<String, InitParams> initParams = Collections.emptyMap();

  public Map<String, InitParams> getInitParams() {
    return initParams;
  }

  protected UpdateHandlerInfo loadUpdatehandlerInfo() {
    return new UpdateHandlerInfo(get("updateHandler/@class", null),
        getInt("updateHandler/autoCommit/maxDocs", -1),
        getInt("updateHandler/autoCommit/maxTime", -1),
        getBool("updateHandler/indexWriter/closeWaitsForMerges", true),
        getBool("updateHandler/autoCommit/openSearcher", true),
        getInt("updateHandler/autoSoftCommit/maxDocs", -1),
        getInt("updateHandler/autoSoftCommit/maxTime", -1),
        getBool("updateHandler/commitWithin/softCommit", true));
  }

  private void loadPluginInfo(SolrPluginInfo pluginInfo) {
    boolean requireName = pluginInfo.options.contains(REQUIRE_NAME);
    boolean requireClass = pluginInfo.options.contains(REQUIRE_CLASS);

    List<PluginInfo> result = readPluginInfos(pluginInfo.tag, requireName, requireClass);

    if (1 < result.size() && !pluginInfo.options.contains(MULTI_OK)) {
      throw new SolrException
          (SolrException.ErrorCode.SERVER_ERROR,
              "Found " + result.size() + " configuration sections when at most "
                  + "1 is allowed matching expression: " + pluginInfo.getCleanTag());
    }
    if (!result.isEmpty()) pluginStore.put(pluginInfo.clazz.getName(), result);
  }

  public List<PluginInfo> readPluginInfos(String tag, boolean requireName, boolean requireClass) {
    ArrayList<PluginInfo> result = new ArrayList<>();
    NodeList nodes = (NodeList) evaluate(tag, XPathConstants.NODESET);
    for (int i = 0; i < nodes.getLength(); i++) {
      PluginInfo pluginInfo = new PluginInfo(nodes.item(i), "[solrconfig.xml] " + tag, requireName, requireClass);
      if (pluginInfo.isEnabled()) result.add(pluginInfo);
    }
    return result;
  }

  public SolrRequestParsers getRequestParsers() {
    return solrRequestParsers;
  }

  /* The set of materialized parameters: */
  public final int booleanQueryMaxClauseCount;
  // SolrIndexSearcher - nutch optimizer -- Disabled since 3.1
//  public final boolean filtOptEnabled;
//  public final int filtOptCacheSize;
//  public final float filtOptThreshold;
  // SolrIndexSearcher - caches configurations
  public final CacheConfig filterCacheConfig;
  public final CacheConfig queryResultCacheConfig;
  public final CacheConfig documentCacheConfig;
  public final CacheConfig fieldValueCacheConfig;
  public final CacheConfig[] userCacheConfigs;
  // SolrIndexSearcher - more...
  public final boolean useFilterForSortedQuery;
  public final int queryResultWindowSize;
  public final int queryResultMaxDocsCached;
  public final boolean enableLazyFieldLoading;
  // DocSet
  public final float hashSetInverseLoadFactor;
  public final int hashDocSetMaxSize;
  // default & main index configurations, deprecated as of 3.6
  @Deprecated
  public final SolrIndexConfig defaultIndexConfig;
  @Deprecated
  public final SolrIndexConfig mainIndexConfig;
  // IndexConfig settings
  public final SolrIndexConfig indexConfig;

  protected UpdateHandlerInfo updateHandlerInfo;

  private Map<String, List<PluginInfo>> pluginStore = new LinkedHashMap<>();

  public final int maxWarmingSearchers;
  public final boolean unlockOnStartup;
  public final boolean useColdSearcher;
  public final Version luceneMatchVersion;
  protected String dataDir;
  public final int slowQueryThresholdMillis;  // threshold above which a query is considered slow

  //JMX configuration
  public final JmxConfiguration jmxConfig;

  private final HttpCachingConfig httpCachingConfig;

  public HttpCachingConfig getHttpCachingConfig() {
    return httpCachingConfig;
  }

  public static class JmxConfiguration implements MapSerializable {
    public boolean enabled = false;
    public String agentId;
    public String serviceUrl;
    public String rootName;

    public JmxConfiguration(boolean enabled,
                            String agentId,
                            String serviceUrl,
                            String rootName) {
      this.enabled = enabled;
      this.agentId = agentId;
      this.serviceUrl = serviceUrl;
      this.rootName = rootName;

      if (agentId != null && serviceUrl != null) {
        throw new SolrException
            (SolrException.ErrorCode.SERVER_ERROR,
                "Incorrect JMX Configuration in solrconfig.xml, " +
                    "both agentId and serviceUrl cannot be specified at the same time");
      }

    }

    @Override
    public Map<String, Object> toMap() {
      LinkedHashMap map = new LinkedHashMap();
      map.put("agentId", agentId);
      map.put("serviceUrl", serviceUrl);
      map.put("rootName", rootName);
      return map;
    }
  }

  public static class HttpCachingConfig implements MapSerializable {

    /**
     * config xpath prefix for getting HTTP Caching options
     */
    private final static String CACHE_PRE
        = "requestDispatcher/httpCaching/";

    /**
     * For extracting Expires "ttl" from <cacheControl> config
     */
    private final static Pattern MAX_AGE
        = Pattern.compile("\\bmax-age=(\\d+)");

    @Override
    public Map<String, Object> toMap() {
      return makeMap("never304", never304,
          "etagSeed", etagSeed,
          "lastModFrom", lastModFrom.name().toLowerCase(Locale.ROOT),
          "cacheControl", cacheControlHeader);
    }

    public static enum LastModFrom {
      OPENTIME, DIRLASTMOD, BOGUS;

      /**
       * Input must not be null
       */
      public static LastModFrom parse(final String s) {
        try {
          return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
          log.warn("Unrecognized value for lastModFrom: " + s, e);
          return BOGUS;
        }
      }
    }

    private final boolean never304;
    private final String etagSeed;
    private final String cacheControlHeader;
    private final Long maxAge;
    private final LastModFrom lastModFrom;

    private HttpCachingConfig(SolrConfig conf) {

      never304 = conf.getBool(CACHE_PRE + "@never304", false);

      etagSeed = conf.get(CACHE_PRE + "@etagSeed", "Solr");


      lastModFrom = LastModFrom.parse(conf.get(CACHE_PRE + "@lastModFrom",
          "openTime"));

      cacheControlHeader = conf.get(CACHE_PRE + "cacheControl", null);

      Long tmp = null; // maxAge
      if (null != cacheControlHeader) {
        try {
          final Matcher ttlMatcher = MAX_AGE.matcher(cacheControlHeader);
          final String ttlStr = ttlMatcher.find() ? ttlMatcher.group(1) : null;
          tmp = (null != ttlStr && !"".equals(ttlStr))
              ? Long.valueOf(ttlStr)
              : null;
        } catch (Exception e) {
          log.warn("Ignoring exception while attempting to " +
              "extract max-age from cacheControl config: " +
              cacheControlHeader, e);
        }
      }
      maxAge = tmp;

    }

    public boolean isNever304() {
      return never304;
    }

    public String getEtagSeed() {
      return etagSeed;
    }

    /**
     * null if no Cache-Control header
     */
    public String getCacheControlHeader() {
      return cacheControlHeader;
    }

    /**
     * null if no max age limitation
     */
    public Long getMaxAge() {
      return maxAge;
    }

    public LastModFrom getLastModFrom() {
      return lastModFrom;
    }
  }

  public static class UpdateHandlerInfo implements MapSerializable {
    public final String className;
    public final int autoCommmitMaxDocs, autoCommmitMaxTime,
        autoSoftCommmitMaxDocs, autoSoftCommmitMaxTime;
    public final boolean indexWriterCloseWaitsForMerges;
    public final boolean openSearcher;  // is opening a new searcher part of hard autocommit?
    public final boolean commitWithinSoftCommit;

    /**
     * @param autoCommmitMaxDocs       set -1 as default
     * @param autoCommmitMaxTime       set -1 as default
     */
    public UpdateHandlerInfo(String className, int autoCommmitMaxDocs, int autoCommmitMaxTime, boolean indexWriterCloseWaitsForMerges, boolean openSearcher,
                             int autoSoftCommmitMaxDocs, int autoSoftCommmitMaxTime, boolean commitWithinSoftCommit) {
      this.className = className;
      this.autoCommmitMaxDocs = autoCommmitMaxDocs;
      this.autoCommmitMaxTime = autoCommmitMaxTime;
      this.indexWriterCloseWaitsForMerges = indexWriterCloseWaitsForMerges;
      this.openSearcher = openSearcher;

      this.autoSoftCommmitMaxDocs = autoSoftCommmitMaxDocs;
      this.autoSoftCommmitMaxTime = autoSoftCommmitMaxTime;

      this.commitWithinSoftCommit = commitWithinSoftCommit;
    }


    @Override
    public Map<String, Object> toMap() {
      LinkedHashMap result = new LinkedHashMap();
      result.put("indexWriter", makeMap("closeWaitsForMerges", indexWriterCloseWaitsForMerges));
      result.put("commitWithin", makeMap("softCommit", commitWithinSoftCommit));
      result.put("autoCommit", makeMap(
          "maxDocs", autoCommmitMaxDocs,
          "maxTime", autoCommmitMaxTime,
          "openSearcher", openSearcher
      ));
      result.put("autoSoftCommit",
          makeMap("maxDocs", autoSoftCommmitMaxDocs,
              "maxTime", autoSoftCommmitMaxTime));
      return result;
    }
  }

//  public Map<String, List<PluginInfo>> getUpdateProcessorChainInfo() { return updateProcessorChainInfo; }

  public UpdateHandlerInfo getUpdateHandlerInfo() {
    return updateHandlerInfo;
  }

  public String getDataDir() {
    return dataDir;
  }

  /**
   * SolrConfig keeps a repository of plugins by the type. The known interfaces are the types.
   *
   * @param type The key is FQN of the plugin class there are a few  known types : SolrFormatter, SolrFragmenter
   *             SolrRequestHandler,QParserPlugin, QueryResponseWriter,ValueSourceParser,
   *             SearchComponent, QueryConverter, SolrEventListener, DirectoryFactory,
   *             IndexDeletionPolicy, IndexReaderFactory, {@link TransformerFactory}
   */
  public List<PluginInfo> getPluginInfos(String type) {
    List<PluginInfo> result = pluginStore.get(type);
    SolrPluginInfo info = classVsSolrPluginInfo.get(type);
    if (info != null &&
        (info.options.contains(REQUIRE_NAME) || info.options.contains(REQUIRE_NAME_IN_OVERLAY))) {
      Map<String, Map> infos = overlay.getNamedPlugins(info.getCleanTag());
      if (!infos.isEmpty()) {
        LinkedHashMap<String, PluginInfo> map = new LinkedHashMap<>();
        if (result != null) for (PluginInfo pluginInfo : result) {
          //just create a UUID for the time being so that map key is not null
          String name = pluginInfo.name == null ?
              UUID.randomUUID().toString().toLowerCase(Locale.ROOT) :
              pluginInfo.name;
          map.put(name, pluginInfo);
        }
        for (Map.Entry<String, Map> e : infos.entrySet()) {
          map.put(e.getKey(), new PluginInfo(info.getCleanTag(), e.getValue()));
        }
        result = new ArrayList<>(map.values());
      }
    }
    return result == null ? Collections.<PluginInfo>emptyList() : result;
  }

  public PluginInfo getPluginInfo(String type) {
    List<PluginInfo> result = pluginStore.get(type);
    if (result == null || result.isEmpty()) {
      return null;
    }
    if (1 == result.size()) {
      return result.get(0);
    }

    throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
        "Multiple plugins configured for type: " + type);
  }

  private void initLibs() {
    NodeList nodes = (NodeList) evaluate("lib", XPathConstants.NODESET);
    if (nodes == null || nodes.getLength() == 0) return;

    log.info("Adding specified lib dirs to ClassLoader");
    ISolrResourceLoader loader = getResourceLoader();

    try {
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);

        String baseDir = DOMUtil.getAttr(node, "dir");
        String path = DOMUtil.getAttr(node, PATH);
        if (null != baseDir) {
          // :TODO: add support for a simpler 'glob' mutually exclusive of regex
          String regex = DOMUtil.getAttr(node, "regex");
          FileFilter filter = (null == regex) ? null : new RegexFileFilter(regex);
          loader.addToClassLoader(baseDir, filter, false);
        } else if (null != path) {
          final File file = FileUtils.resolvePath(new File(loader.getInstanceDir()), path);
          loader.addToClassLoader(file.getParent(), new FileFilter() {
            @Override
            public boolean accept(File pathname) {
              return pathname.equals(file);
            }
          }, false);
        } else {
          throw new RuntimeException(
              "lib: missing mandatory attributes: 'dir' or 'path'");
        }
      }
    } finally {
      loader.reloadLuceneSPI();
    }
  }

  public int getMultipartUploadLimitKB() {
    return multipartUploadLimitKB;
  }

  public int getFormUploadLimitKB() {
    return formUploadLimitKB;
  }

  public boolean isHandleSelect() {
    return handleSelect;
  }

  public boolean isAddHttpRequestToContext() {
    return addHttpRequestToContext;
  }

  public boolean isEnableRemoteStreams() {
    return enableRemoteStreams;
  }

  @Override
  public int getInt(String path) {
    return getInt(path, 0);
  }

  @Override
  public int getInt(String path, int def) {
    Object val = overlay.getXPathProperty(path);
    if (val != null) return Integer.parseInt(val.toString());
    return super.getInt(path, def);
  }

  @Override
  public boolean getBool(String path, boolean def) {
    Object val = overlay.getXPathProperty(path);
    if (val != null) return Boolean.parseBoolean(val.toString());
    return super.getBool(path, def);
  }

  @Override
  public String get(String path) {
    Object val = overlay.getXPathProperty(path, true);
    return val != null ? val.toString() : super.get(path);
  }

  @Override
  public String get(String path, String def) {
    Object val = overlay.getXPathProperty(path, true);
    return val != null ? val.toString() : super.get(path, def);

  }

  @Override
  public Map<String, Object> toMap() {
    LinkedHashMap result = new LinkedHashMap();
    if (getZnodeVersion() > -1) result.put(ZNODEVER, getZnodeVersion());
    result.put("luceneMatchVersion", luceneMatchVersion);
    result.put("updateHandler", getUpdateHandlerInfo().toMap());
    Map m = new LinkedHashMap();
    result.put("query", m);
    m.put("useFilterForSortedQuery", useFilterForSortedQuery);
    m.put("queryResultWindowSize", queryResultWindowSize);
    m.put("queryResultMaxDocsCached", queryResultMaxDocsCached);
    m.put("enableLazyFieldLoading", enableLazyFieldLoading);
    m.put("maxBooleanClauses", booleanQueryMaxClauseCount);
    if (jmxConfig != null) result.put("jmx", jmxConfig.toMap());
    for (SolrPluginInfo plugin : plugins) {
      List<PluginInfo> infos = getPluginInfos(plugin.clazz.getName());
      if (infos == null || infos.isEmpty()) continue;
      String tag = plugin.getCleanTag();
      tag = tag.replace("/", "");
      if (plugin.options.contains(PluginOpts.REQUIRE_NAME)) {
        LinkedHashMap items = new LinkedHashMap();
        for (PluginInfo info : infos) items.put(info.name, info.toMap());
        for (Map.Entry e : overlay.getNamedPlugins(plugin.tag).entrySet()) items.put(e.getKey(), e.getValue());
        result.put(tag, items);
      } else {
        if (plugin.options.contains(MULTI_OK)) {
          ArrayList<Map> l = new ArrayList<>();
          for (PluginInfo info : infos) l.add(info.toMap());
          result.put(tag, l);
        } else {
          result.put(tag, infos.get(0).toMap());
        }

      }

    }


    addCacheConfig(m, filterCacheConfig, queryResultCacheConfig, documentCacheConfig, fieldValueCacheConfig);
    if (jmxConfig != null) result.put("jmx", jmxConfig.toMap());
    m = new LinkedHashMap();
    result.put("requestDispatcher", m);
    m.put("handleSelect", handleSelect);
    if (httpCachingConfig != null) m.put("httpCaching", httpCachingConfig.toMap());
    m.put("requestParsers", makeMap("multipartUploadLimitKB", multipartUploadLimitKB,
        "formUploadLimitKB", formUploadLimitKB,
        "addHttpRequestToContext", addHttpRequestToContext));
    if (indexConfig != null) result.put("indexConfig", indexConfig.toMap());

    //TODO there is more to add

    return result;
  }

  private void addCacheConfig(Map queryMap, CacheConfig... cache) {
    if (cache == null) return;
    for (CacheConfig config : cache) if (config != null) queryMap.put(config.getNodeName(), config.toMap());

  }

  @Override
  protected Properties getSubstituteProperties() {
    Map<String, Object> p = getOverlay().getUserProps();
    if (p == null || p.isEmpty()) return super.getSubstituteProperties();
    Properties result = new Properties(super.getSubstituteProperties());
    result.putAll(p);
    return result;
  }

  private ConfigOverlay overlay;

  public ConfigOverlay getOverlay() {
    if (overlay == null) {
      overlay = getConfigOverlay(getResourceLoader());
    }
    return overlay;
  }

  public RequestParams getRequestParams() {
    if (requestParams == null) {
      return refreshRequestParams();
    }
    return requestParams;
  }


  public RequestParams refreshRequestParams() {
    requestParams = RequestParams.getFreshRequestParams(getResourceLoader(), requestParams);
    log.info("current version of requestparams : {}", requestParams.getZnodeVersion());
    return requestParams;
  }

}
