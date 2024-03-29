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

package edu.umkc.solr.update.processor;

import static org.apache.solr.common.SolrException.ErrorCode.*;

import org.apache.solr.common.SolrException;

import edu.umkc.type.ISolrCore;

import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;

/**
 * An update processor that keeps only the the maximum value from any selected 
 * fields where multiple values are found.  Correct behavior requires tha all 
 * of the values in the SolrInputFields being mutated are mutually comparable; 
 * If this is not the case, then a SolrException will br thrown. 
 * <p>
 * By default, this processor matches no fields.
 * </p>
 *
 * <p>
 * In the example configuration below, if a document contains multiple integer 
 * values (ie: <code>64, 128, 1024</code>) in the field 
 * <code>largestFileSize</code> then only the biggest value 
 * (ie: <code>1024</code>) will be kept in that field.
 * <br>
 *
 * <pre class="prettyprint">
 *  &lt;processor class="solr.MaxFieldValueUpdateProcessorFactory"&gt;
 *    &lt;str name="fieldName"&gt;largestFileSize&lt;/str&gt;
 *  &lt;/processor&gt;
 * </pre>
 *
 * @see MinFieldValueUpdateProcessorFactory
 * @see Collections#max
 */
public final class MaxFieldValueUpdateProcessorFactory extends FieldValueSubsetUpdateProcessorFactory {

  @Override
  @SuppressWarnings("unchecked")
  public Collection pickSubset(Collection values) {
    Collection result = values;
    try {
      result = Collections.singletonList
        (Collections.max(values));
    } catch (ClassCastException e) {
      throw new SolrException
        (BAD_REQUEST, 
         "Field values are not mutually comparable: " + e.getMessage(), e);
    }
    return result;
  }

  @Override
  public FieldMutatingUpdateProcessor.FieldNameSelector 
    getDefaultSelector(final ISolrCore core) {
    
    return FieldMutatingUpdateProcessor.SELECT_NO_FIELDS;
  }
  
}

