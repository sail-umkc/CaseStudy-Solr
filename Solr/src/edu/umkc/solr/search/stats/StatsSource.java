package edu.umkc.solr.search.stats;

/**
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

import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.Weight;

import comp.SolrIndexSearcherFactory.SolrIndexSearcherFactoryImp.SolrIndexSearcher;

/**
 * The purpose of this class is only to provide two pieces of information
 * necessary to create {@link Weight} from a {@link Query}, that is
 * {@link TermStatistics} for a term and {@link CollectionStatistics} for the
 * whole collection.
 */
public abstract class StatsSource {
  
  public abstract TermStatistics termStatistics(SolrIndexSearcher localSearcher, Term term, TermContext context)
      throws IOException;
  
  public abstract CollectionStatistics collectionStatistics(SolrIndexSearcher localSearcher, String field)
      throws IOException;
}
