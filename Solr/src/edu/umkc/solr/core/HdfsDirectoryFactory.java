package edu.umkc.solr.core;

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

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.NRTCachingDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.SingleInstanceLockFactory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import comp.SolrResourceLoader.SolrResourceLoaderImp;

import edu.umkc.solr.cloud.ZkController;
import edu.umkc.solr.store.blockcache.BlockCache;
import edu.umkc.solr.store.blockcache.BlockDirectory;
import edu.umkc.solr.store.blockcache.BlockDirectoryCache;
import edu.umkc.solr.store.blockcache.BufferStore;
import edu.umkc.solr.store.blockcache.Cache;
import edu.umkc.solr.store.blockcache.Metrics;
import edu.umkc.solr.store.hdfs.HdfsDirectory;
import edu.umkc.solr.store.hdfs.HdfsLockFactory;
import edu.umkc.solr.util.HdfsUtil;

public class HdfsDirectoryFactory extends CachingDirectoryFactory {
  public static Logger LOG = LoggerFactory
      .getLogger(HdfsDirectoryFactory.class);
  
  public static final String BLOCKCACHE_SLAB_COUNT = "solr.hdfs.blockcache.slab.count";
  public static final String BLOCKCACHE_DIRECT_MEMORY_ALLOCATION = "solr.hdfs.blockcache.direct.memory.allocation";
  public static final String BLOCKCACHE_ENABLED = "solr.hdfs.blockcache.enabled";
  public static final String BLOCKCACHE_GLOBAL = "solr.hdfs.blockcache.global";
  public static final String BLOCKCACHE_READ_ENABLED = "solr.hdfs.blockcache.read.enabled";
  public static final String BLOCKCACHE_WRITE_ENABLED = "solr.hdfs.blockcache.write.enabled"; // currently buggy and disabled
  
  public static final String NRTCACHINGDIRECTORY_ENABLE = "solr.hdfs.nrtcachingdirectory.enable";
  public static final String NRTCACHINGDIRECTORY_MAXMERGESIZEMB = "solr.hdfs.nrtcachingdirectory.maxmergesizemb";
  public static final String NRTCACHINGDIRECTORY_MAXCACHEMB = "solr.hdfs.nrtcachingdirectory.maxcachedmb";
  public static final String NUMBEROFBLOCKSPERBANK = "solr.hdfs.blockcache.blocksperbank";
  
  public static final String KERBEROS_ENABLED = "solr.hdfs.security.kerberos.enabled";
  public static final String KERBEROS_KEYTAB = "solr.hdfs.security.kerberos.keytabfile";
  public static final String KERBEROS_PRINCIPAL = "solr.hdfs.security.kerberos.principal";
  
  public static final String HDFS_HOME = "solr.hdfs.home";
  
  public static final String CONFIG_DIRECTORY = "solr.hdfs.confdir";
  
  private SolrParams params;
  
  private String hdfsDataDir;
  
  private String confDir;

  private static BlockCache globalBlockCache;
  
  public static Metrics metrics;
  private static Boolean kerberosInit;
  
  // we use this cache for FileSystem instances when we don't have access to a long lived instance
  private com.google.common.cache.Cache<String,FileSystem> tmpFsCache = CacheBuilder.newBuilder()
      .concurrencyLevel(10)
      .maximumSize(1000)
      .expireAfterAccess(5, TimeUnit.MINUTES).removalListener(new RemovalListener<String,FileSystem>() {
        @Override
        public void onRemoval(RemovalNotification<String,FileSystem> rn) {
          IOUtils.closeQuietly(rn.getValue());
        }
      })
      .build();
  
  private final static class MetricsHolder {
    // [JCIP SE, Goetz, 16.6] Lazy initialization
    // Won't load until MetricsHolder is referenced
    public static final Metrics metrics = new Metrics();
  }
  
  @Override
  public void close() throws IOException {
    super.close();
    Collection<FileSystem> values = tmpFsCache.asMap().values();
    for (FileSystem fs : values) {
      IOUtils.closeQuietly(fs);
    }
    tmpFsCache.invalidateAll();
    tmpFsCache.cleanUp();
  }

  @Override
  public void init(NamedList args) {
    params = SolrParams.toSolrParams(args);
    this.hdfsDataDir = getConfig(HDFS_HOME, null);
    if (this.hdfsDataDir != null && this.hdfsDataDir.length() == 0) {
      this.hdfsDataDir = null;
    } else {
      LOG.info(HDFS_HOME + "=" + this.hdfsDataDir);
    }
    boolean kerberosEnabled = getConfig(KERBEROS_ENABLED, false);
    LOG.info("Solr Kerberos Authentication "
        + (kerberosEnabled ? "enabled" : "disabled"));
    if (kerberosEnabled) {
      initKerberos();
    }
  }
  
  @Override
  protected LockFactory createLockFactory(String rawLockType) throws IOException {
    if (null == rawLockType) {
      LOG.warn("No lockType configured, assuming 'hdfs'.");
      rawLockType = "hdfs";
    }
    final String lockType = rawLockType.toLowerCase(Locale.ROOT).trim();
    switch (lockType) {
      case "hdfs":
        return HdfsLockFactory.INSTANCE;
      case "single":
        return new SingleInstanceLockFactory();
      case "none":
        return NoLockFactory.INSTANCE;
      default:
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "Unrecognized lockType: " + rawLockType);
    }
  }

  @Override
  protected Directory create(String path, LockFactory lockFactory, DirContext dirContext) throws IOException {
    assert params != null : "init must be called before create";
    LOG.info("creating directory factory for path {}", path);
    Configuration conf = getConf();
    
    if (metrics == null) {
      metrics = MetricsHolder.metrics;
    }
    
    boolean blockCacheEnabled = getConfig(BLOCKCACHE_ENABLED, true);
    boolean blockCacheGlobal = getConfig(BLOCKCACHE_GLOBAL, false); // default to false for back compat
    boolean blockCacheReadEnabled = getConfig(BLOCKCACHE_READ_ENABLED, true);
    
    final Directory dir;
    if (blockCacheEnabled && dirContext != DirContext.META_DATA) {
      int numberOfBlocksPerBank = getConfig(NUMBEROFBLOCKSPERBANK, 16384);
      
      int blockSize = BlockDirectory.BLOCK_SIZE;
      
      int bankCount = getConfig(BLOCKCACHE_SLAB_COUNT, 1);
      
      boolean directAllocation = getConfig(BLOCKCACHE_DIRECT_MEMORY_ALLOCATION, true);
      
      int slabSize = numberOfBlocksPerBank * blockSize;
      LOG.info(
          "Number of slabs of block cache [{}] with direct memory allocation set to [{}]",
          bankCount, directAllocation);
      LOG.info(
          "Block cache target memory usage, slab size of [{}] will allocate [{}] slabs and use ~[{}] bytes",
          new Object[] {slabSize, bankCount,
              ((long) bankCount * (long) slabSize)});
      
      int bufferSize = getConfig("solr.hdfs.blockcache.bufferstore.buffersize", 128);
      int bufferCount = getConfig("solr.hdfs.blockcache.bufferstore.buffercount", 128 * 128);
      
      BlockCache blockCache = getBlockDirectoryCache(numberOfBlocksPerBank,
          blockSize, bankCount, directAllocation, slabSize,
          bufferSize, bufferCount, blockCacheGlobal);
      
      Cache cache = new BlockDirectoryCache(blockCache, path, metrics, blockCacheGlobal);
      HdfsDirectory hdfsDirectory = new HdfsDirectory(new Path(path), lockFactory, conf);
      dir = new BlockDirectory(path, hdfsDirectory, cache, null, blockCacheReadEnabled, false);
    } else {
      dir = new HdfsDirectory(new Path(path), lockFactory, conf);
    }
    
    boolean nrtCachingDirectory = getConfig(NRTCACHINGDIRECTORY_ENABLE, true);
    if (nrtCachingDirectory) {
      double nrtCacheMaxMergeSizeMB = getConfig(NRTCACHINGDIRECTORY_MAXMERGESIZEMB, 16);
      double nrtCacheMaxCacheMB = getConfig(NRTCACHINGDIRECTORY_MAXCACHEMB, 192);
      
      return new NRTCachingDirectory(dir, nrtCacheMaxMergeSizeMB, nrtCacheMaxCacheMB);
    }
    return dir;
  }

  boolean getConfig(String name, boolean defaultValue) {
    Boolean value = params.getBool(name);
    if (value == null) {
      String sysValue = System.getProperty(name);
      if (sysValue != null) {
        value = Boolean.valueOf(sysValue);
      }
    }
    return value == null ? defaultValue : value;
  }
  
  int getConfig(String name, int defaultValue) {
    Integer value = params.getInt(name);
    if (value == null) {
      String sysValue = System.getProperty(name);
      if (sysValue != null) {
        value = Integer.parseInt(sysValue);
      }
    }
    return value == null ? defaultValue : value;
  }

  String getConfig(String name, String defaultValue) {
    String value = params.get(name);
    if (value == null) {
      value = System.getProperty(name);
    }
    return value == null ? defaultValue : value;
  }
  
  private BlockCache getBlockDirectoryCache(int numberOfBlocksPerBank, int blockSize, int bankCount,
      boolean directAllocation, int slabSize, int bufferSize, int bufferCount, boolean staticBlockCache) {
    if (!staticBlockCache) {
      LOG.info("Creating new single instance HDFS BlockCache");
      return createBlockCache(numberOfBlocksPerBank, blockSize, bankCount, directAllocation, slabSize, bufferSize, bufferCount);
    }
    synchronized (HdfsDirectoryFactory.class) {
      
      if (globalBlockCache == null) {
        LOG.info("Creating new global HDFS BlockCache");
        globalBlockCache = createBlockCache(numberOfBlocksPerBank, blockSize, bankCount,
            directAllocation, slabSize, bufferSize, bufferCount);
      }
    }
    return globalBlockCache;
  }

  private BlockCache createBlockCache(int numberOfBlocksPerBank, int blockSize,
      int bankCount, boolean directAllocation, int slabSize, int bufferSize,
      int bufferCount) {
    BufferStore.initNewBuffer(bufferSize, bufferCount, metrics);
    long totalMemory = (long) bankCount * (long) numberOfBlocksPerBank
        * (long) blockSize;
    
    BlockCache blockCache;
    try {
      blockCache = new BlockCache(metrics, directAllocation, totalMemory, slabSize, blockSize);
    } catch (OutOfMemoryError e) {
      throw new RuntimeException(
          "The max direct memory is likely too low.  Either increase it (by adding -XX:MaxDirectMemorySize=<size>g -XX:+UseLargePages to your containers startup args)"
              + " or disable direct allocation using solr.hdfs.blockcache.direct.memory.allocation=false in solrconfig.xml. If you are putting the block cache on the heap,"
              + " your java heap size might not be large enough."
              + " Failed allocating ~" + totalMemory / 1000000.0 + " MB.",
          e);
    }
    return blockCache;
  }
  
  @Override
  public boolean exists(String path) {
    final Path hdfsDirPath = new Path(path);
    final Configuration conf = getConf();
    FileSystem fileSystem = null;
    try {
      // no need to close the fs, the cache will do it
      fileSystem = tmpFsCache.get(path, new Callable<FileSystem>() {
        @Override
        public FileSystem call() throws IOException {
          return FileSystem.get(hdfsDirPath.toUri(), conf);
        }
      });
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    try {
      return fileSystem.exists(hdfsDirPath);
    } catch (IOException e) {
      LOG.error("Error checking if hdfs path exists", e);
      throw new RuntimeException("Error checking if hdfs path exists", e);
    }
  }
  
  private Configuration getConf() {
    Configuration conf = new Configuration();
    confDir = getConfig(CONFIG_DIRECTORY, null);
    HdfsUtil.addHdfsResources(conf, confDir);
    conf.setBoolean("fs.hdfs.impl.disable.cache", true);
    return conf;
  }
  
  protected synchronized void removeDirectory(final CacheValue cacheValue)
      throws IOException {
    final Configuration conf = getConf();
    FileSystem fileSystem = null;
    
    try {
      // no need to close the fs, the cache will do it
      fileSystem = tmpFsCache.get(cacheValue.path, new Callable<FileSystem>() {
        @Override
        public FileSystem call() throws IOException {
          return FileSystem.get(new Path(cacheValue.path).toUri(), conf);
        }
      });
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    
    try {
      boolean success = fileSystem.delete(new Path(cacheValue.path), true);
      if (!success) {
        throw new RuntimeException("Could not remove directory");
      }
    } catch (Exception e) {
      LOG.error("Could not remove directory", e);
      throw new SolrException(ErrorCode.SERVER_ERROR,
          "Could not remove directory", e);
    }
  }
  
  @Override
  public boolean isAbsolute(String path) {
    return path.startsWith("hdfs:/");
  }
  
  @Override
  public boolean isPersistent() {
    return true;
  }
  
  @Override
  public boolean isSharedStorage() {
    return true;
  }
  
  @Override
  public boolean searchersReserveCommitPoints() {
    return true;
  }
  
  @Override
  public String getDataHome(CoreDescriptor cd) throws IOException {
    if (hdfsDataDir == null) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "You must set the "
          + this.getClass().getSimpleName() + " param " + HDFS_HOME
          + " for relative dataDir paths to work");
    }
    
    // by default, we go off the instance directory
    String path;
    if (cd.getCloudDescriptor() != null) {
      path = URLEncoder.encode(cd.getCloudDescriptor().getCollectionName(),
          "UTF-8")
          + "/"
          + URLEncoder.encode(cd.getCloudDescriptor().getCoreNodeName(),
              "UTF-8");
    } else {
      path = cd.getName();
    }
    
    return normalize(SolrResourceLoaderImp.normalizeDir(ZkController
        .trimLeadingAndTrailingSlashes(hdfsDataDir)
        + "/"
        + path
        + "/"
        + cd.getDataDir()));
  }
  
  public String getConfDir() {
    return confDir;
  }
  
  private void initKerberos() {
    String keytabFile = getConfig(KERBEROS_KEYTAB, "").trim();
    if (keytabFile.length() == 0) {
      throw new IllegalArgumentException(KERBEROS_KEYTAB + " required because "
          + KERBEROS_ENABLED + " set to true");
    }
    String principal = getConfig(KERBEROS_PRINCIPAL, "");
    if (principal.length() == 0) {
      throw new IllegalArgumentException(KERBEROS_PRINCIPAL
          + " required because " + KERBEROS_ENABLED + " set to true");
    }
    synchronized (HdfsDirectoryFactory.class) {
      if (kerberosInit == null) {
        kerberosInit = new Boolean(true);
        final Configuration conf = getConf();
        final String authVal = conf.get(HADOOP_SECURITY_AUTHENTICATION);
        final String kerberos = "kerberos";
        if (authVal != null && !authVal.equals(kerberos)) {
          throw new IllegalArgumentException(HADOOP_SECURITY_AUTHENTICATION
              + " set to: " + authVal + ", not kerberos, but attempting to "
              + " connect to HDFS via kerberos");
        }
        // let's avoid modifying the supplied configuration, just to be conservative
        final Configuration ugiConf = new Configuration(getConf());
        ugiConf.set(HADOOP_SECURITY_AUTHENTICATION, kerberos);
        UserGroupInformation.setConfiguration(ugiConf);
        LOG.info(
            "Attempting to acquire kerberos ticket with keytab: {}, principal: {} ",
            keytabFile, principal);
        try {
          UserGroupInformation.loginUserFromKeytab(principal, keytabFile);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }
        LOG.info("Got Kerberos ticket");
      }
    }
  }

  @Override
  public Collection<SolrInfoMBean> offerMBeans() {
    return Arrays.<SolrInfoMBean>asList(MetricsHolder.metrics);
  }
}
