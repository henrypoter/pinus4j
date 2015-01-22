/**
 * Copyright 2014 Duan Bingnan
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 *   
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pinus.cluster.impl;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import javax.sql.DataSource;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.pinus.api.IShardingKey;
import org.pinus.api.enums.EnumDB;
import org.pinus.api.enums.EnumDBMasterSlave;
import org.pinus.api.enums.EnumDBRouteAlg;
import org.pinus.api.enums.EnumSyncAction;
import org.pinus.cache.ICacheBuilder;
import org.pinus.cache.IPrimaryCache;
import org.pinus.cache.ISecondCache;
import org.pinus.cache.impl.MemCachedCacheBuilder;
import org.pinus.cluster.DB;
import org.pinus.cluster.IDBCluster;
import org.pinus.cluster.ITableCluster;
import org.pinus.cluster.ITableClusterBuilder;
import org.pinus.cluster.beans.DBClusterInfo;
import org.pinus.cluster.beans.DBClusterRegionInfo;
import org.pinus.cluster.beans.DBInfo;
import org.pinus.cluster.route.IClusterRouter;
import org.pinus.cluster.route.RouteInfo;
import org.pinus.cluster.route.impl.SimpleHashClusterRouter;
import org.pinus.config.IClusterConfig;
import org.pinus.config.impl.XmlClusterConfigImpl;
import org.pinus.constant.Const;
import org.pinus.datalayer.IDataLayerBuilder;
import org.pinus.datalayer.jdbc.JdbcDataLayerBuilder;
import org.pinus.exception.DBClusterException;
import org.pinus.exception.DBOperationException;
import org.pinus.exception.DBRouteException;
import org.pinus.exception.LoadConfigException;
import org.pinus.generator.DefaultDBGeneratorBuilder;
import org.pinus.generator.IDBGenerator;
import org.pinus.generator.IDBGeneratorBuilder;
import org.pinus.generator.IIdGenerator;
import org.pinus.generator.beans.DBTable;
import org.pinus.generator.impl.DistributedSequenceIdGeneratorImpl;
import org.pinus.util.CuratorDistributeedLock;
import org.pinus.util.IOUtil;
import org.pinus.util.ReflectUtil;
import org.pinus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽象数据库集群. 主要负责初始化数据库集群的数据源对象、分表信息.<br/>
 * need to invoke startup method before use it, invoke shutdown method at last.
 * 
 * @author duanbn
 */
public abstract class AbstractDBCluster implements IDBCluster {

	/**
	 * 日志
	 */
	private static final Logger LOG = LoggerFactory.getLogger(AbstractDBCluster.class);

	/**
	 * 同步数据表操作.
	 */
	private EnumSyncAction syncAction = EnumSyncAction.CREATE;

	/**
	 * 扫描数据对象包.
	 */
	private String scanPackage;

	/**
	 * 数据分片信息是否从zookeeper中获取.
	 */
	private boolean isShardInfoFromZk;

	/**
	 * 数据库类型.
	 */
	protected EnumDB enumDb = EnumDB.MYSQL;

	/**
	 * 路由算法. 默认使用取模哈希算法
	 */
	protected EnumDBRouteAlg enumDBRouteAlg = EnumDBRouteAlg.SIMPLE_HASH;

	/**
	 * 数据库表生成器.
	 */
	private IDBGenerator dbGenerator;

	/**
	 * 主键生成器. 默认使用SimpleIdGeneratorImpl生成器.
	 */
	private IIdGenerator idGenerator;

	/**
	 * 一级缓存.
	 */
	private IPrimaryCache primaryCache;

	/**
	 * 二级缓存.
	 */
	private ISecondCache secondCache;

	/**
	 * 数据库路由器
	 */
	private IClusterRouter dbRouter;

	/**
	 * cluster info. {clusterName, clusterInfo}
	 */
	private Map<String, DBClusterInfo> dbClusterInfo;

	/**
	 * 集群中的表集合. {集群名称, {分库下标, {表名, 分表数}}}
	 */
	private ITableCluster tableCluster;

	/**
	 * 集群配置.
	 */
	private IClusterConfig config;

	/**
	 * curator client.
	 */
	private CuratorFramework curatorClient;

	/**
	 * 构造方法.
	 * 
	 * @param enumDb
	 *            数据库类型.
	 */
	public AbstractDBCluster(EnumDB enumDb) {
		this.enumDb = enumDb;
	}

	@Override
	public Collection<DBClusterInfo> getDBClusterInfo() {
		return this.dbClusterInfo.values();
	}

	@Override
	public DBClusterInfo getDBClusterInfo(String clusterName) {
		DBClusterInfo clusterInfo = dbClusterInfo.get(clusterName);

		if (clusterInfo == null) {
			throw new DBOperationException("找不到集群信息, clusterName=" + clusterName);
		}

		return clusterInfo;
	}

	@Override
	public void startup() throws DBClusterException {
		startup(null);
	}

	@Override
	public void startup(String xmlFilePath) throws DBClusterException {
		LOG.info("start init database cluster");

		// load storage-config.xml
		try {
			config = _getConfig(xmlFilePath);
		} catch (LoadConfigException e) {
			throw new RuntimeException(e);
		}

		// 初始化curator framework
		this.curatorClient = CuratorFrameworkFactory.newClient(config.getZookeeperUrl(), new RetryNTimes(5, 1000));
		this.curatorClient.start();

		try {
			// 创建zookeeper root dir
			ZooKeeper zkClient = this.curatorClient.getZookeeperClient().getZooKeeper();
			Stat stat = zkClient.exists(Const.ZK_ROOT, false);
			if (stat == null) {
				zkClient.create(Const.ZK_ROOT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}
		} catch (Exception e) {
			throw new IllegalStateException("初始化zookeeper根目录失败");
		}

		//
		// init id generator
		//
		this.idGenerator = new DistributedSequenceIdGeneratorImpl(config, this.curatorClient);
		LOG.info("init primary key generator done");

		//
		// init db cache
		//
		ICacheBuilder cacheBuilder = MemCachedCacheBuilder.valueOf(config);
		// 发现可用的一级缓存
		this.primaryCache = cacheBuilder.buildPrimaryCache();

		// 发现可用的二级缓存
		this.secondCache = cacheBuilder.buildSecondCache();

		//
		// init db generator
		//
		IDBGeneratorBuilder dbGeneratorBuilder = DefaultDBGeneratorBuilder.valueOf(this.syncAction, this.enumDb);
		this.dbGenerator = dbGeneratorBuilder.build();

		//
		// 加载DB集群信息
		//
		dbClusterInfo = config.getDBClusterInfo();
		try {
			// 初始化主集群连接
			_initDBCluster(this.dbClusterInfo);

			// 初始化数据表集群信息.
			List<DBTable> tables = null;
			if (isShardInfoFromZk) {
				// get table sharding info from zookeeper
				tables = getDBTableFromZk();
			} else {
				if (StringUtils.isBlank(scanPackage)) {
					throw new DBClusterException(
							"get shardinfo from jvm, but i can't find scanpackage full path, did you forget setScanPackage ?");
				}

				// get table sharding info from jvm
				tables = getDBTableFromJvm();
				// 表分片信息写入zookeeper
				_syncToZookeeper(tables);
			}
			if (tables.isEmpty()) {
				throw new DBClusterException("找不到可以创建库表的实体对象, package=" + scanPackage);
			}

			// 初始化表集群
			ITableClusterBuilder tableClusterBuilder = NumberIndexTableClusterBuilder.valueOf(tables);
			this.tableCluster = tableClusterBuilder.build();

			// 创建数据库表
			if (this.syncAction != EnumSyncAction.NONE) {
				LOG.info("syncing table info");
				long start = System.currentTimeMillis();
				_createTable(tables);
				LOG.info("sync table info done, const time:" + (System.currentTimeMillis() - start) + "ms");
			}

		} catch (Exception e) {
			throw new DBClusterException("init database cluster failure", e);
		}

		//
		// init db router
		//
		switch (enumDBRouteAlg) {
		case SIMPLE_HASH:
			dbRouter = new SimpleHashClusterRouter();
			break;
		default:
			dbRouter = new SimpleHashClusterRouter();
			break;
		}
		// set db cluster info.
		this.dbRouter.setDBCluster(this);
		// set table cluster info.
		this.dbRouter.setTableCluster(tableCluster);
		// set hash algo
		this.dbRouter.setHashAlgo(config.getHashAlgo());

		LOG.info("init database cluster done.");
	}

	/**
	 * relase resource. include database connection,zookeeper connection and
	 * cache connection.
	 */
	@Override
	public void shutdown() throws DBClusterException {

		// close cache connection
        if (this.primaryCache != null)
            this.primaryCache.close();
        if (this.secondCache != null)
            this.secondCache.close();

		try {
			// close database connection
			for (Map.Entry<String, DBClusterInfo> entry : this.dbClusterInfo.entrySet()) {
				// 关闭全局库
				// 主全局库
				DBInfo masterGlobal = entry.getValue().getMasterGlobalDBInfo();
				if (masterGlobal != null)
					closeDataSource(masterGlobal);

				// 从全局库
				List<DBInfo> slaveDbs = entry.getValue().getSlaveGlobalDBInfo();
				if (slaveDbs != null && !slaveDbs.isEmpty()) {
					for (DBInfo slaveGlobal : slaveDbs) {
						closeDataSource(slaveGlobal);
					}
				}

				// 关闭集群库
				for (DBClusterRegionInfo regionInfo : entry.getValue().getDbRegions()) {
					// 主集群
					for (DBInfo dbConnInfo : regionInfo.getMasterConnection()) {
						closeDataSource(dbConnInfo);
					}

					// 从集群
					for (List<DBInfo> dbConnInfos : regionInfo.getSlaveConnection()) {
						for (DBInfo dbConnInfo : dbConnInfos) {
							closeDataSource(dbConnInfo);
						}
					}
				}
			}

		} catch (Exception e) {
			throw new DBClusterException("关闭数据库集群失败", e);
		}

		// close curator
		CloseableUtils.closeQuietly(this.curatorClient);
	}

	@Override
	public DBInfo getMasterGlobalConn(String clusterName) throws DBClusterException {
		DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
		if (dbClusterInfo == null) {
			throw new DBClusterException("没有找到集群信息, clustername=" + clusterName);
		}

		DBInfo masterConnection = dbClusterInfo.getMasterGlobalDBInfo();
		if (masterConnection == null) {
			throw new DBClusterException("此集群没有配置全局主库, clustername=" + clusterName);
		}
		return masterConnection;
	}

	@Override
	public DBInfo getSlaveGlobalDbConn(String clusterName, EnumDBMasterSlave slave) throws DBClusterException {
		DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
		if (dbClusterInfo == null) {
			throw new DBClusterException("没有找到集群信息, clustername=" + clusterName);
		}

		List<DBInfo> slaveDbs = dbClusterInfo.getSlaveGlobalDBInfo();
		if (slaveDbs == null || slaveDbs.isEmpty()) {
			throw new DBClusterException("此集群没有配置全局从库, clustername=" + clusterName);
		}
		DBInfo slaveConnection = slaveDbs.get(slave.getValue());
		return slaveConnection;
	}

	@Override
	public DB selectDbFromMaster(String tableName, IShardingKey<?> value) throws DBClusterException {

		// 计算分库
		// 计算路由信息
		RouteInfo routeInfo = null;
		try {
			routeInfo = dbRouter.select(EnumDBMasterSlave.MASTER, tableName, value);
		} catch (DBRouteException e) {
			throw new DBClusterException(e);
		}
		String clusterName = routeInfo.getClusterName();
		int dbIndex = routeInfo.getDbIndex();
		int tableIndex = routeInfo.getTableIndex();

		// 获取连接信息
		DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
		if (dbClusterInfo == null) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName);
		}
		DBClusterRegionInfo regionInfo = dbClusterInfo.getDbRegions().get(routeInfo.getRegionIndex());
		if (regionInfo == null) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName);
		}
		List<DBInfo> masterConntions = regionInfo.getMasterConnection();
		if (masterConntions == null || masterConntions.isEmpty()) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName);
		}

		DataSource datasource = masterConntions.get(dbIndex).getDatasource();

		// 返回分库分表信息
		DB db = new DB();
		db.setDatasource(datasource);
		db.setTableName(tableName);
		db.setTableIndex(tableIndex);
		db.setClusterName(clusterName);
		db.setDbIndex(dbIndex);
		db.setDbCluster(this);
		db.setStart(regionInfo.getStart());
		db.setEnd(regionInfo.getEnd());

		return db;
	}

	@Override
	public DB selectDbFromSlave(String tableName, IShardingKey<?> value, EnumDBMasterSlave slaveNum)
			throws DBClusterException {

		// 计算分库
		// 计算路由信息
		RouteInfo routeInfo = null;
		try {
			routeInfo = dbRouter.select(slaveNum, tableName, value);
		} catch (DBRouteException e) {
			throw new DBClusterException(e);
		}
		// 获取分库分表的下标
		String clusterName = routeInfo.getClusterName();
		int dbIndex = routeInfo.getDbIndex();
		int tableIndex = routeInfo.getTableIndex();

		// 获取连接信息
		DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
		if (dbClusterInfo == null) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName + ", slavenum="
					+ slaveNum.getValue());
		}
		DBClusterRegionInfo regionInfo = dbClusterInfo.getDbRegions().get(routeInfo.getRegionIndex());
		if (regionInfo == null) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName + ", slavenum="
					+ slaveNum.getValue());
		}
		List<DBInfo> slaveConnections = regionInfo.getSlaveConnection().get(slaveNum.getValue());
		if (slaveConnections == null || slaveConnections.isEmpty()) {
			throw new DBClusterException("找不到数据库集群, shardingkey=" + value + ", tablename=" + tableName + ", slavenum="
					+ slaveNum.getValue());
		}

		DataSource datasource = slaveConnections.get(dbIndex).getDatasource();

		// 返回分库分表信息
		DB db = new DB();
		db.setDatasource(datasource);
		db.setClusterName(clusterName);
		db.setDbIndex(dbIndex);
		db.setTableName(tableName);
		db.setTableIndex(tableIndex);
		db.setDbCluster(this);
		db.setStart(regionInfo.getStart());
		db.setEnd(regionInfo.getEnd());

		return db;
	}

	@Override
	public List<DB> getAllMasterShardingDB(int tableNum, String clusterName, String tableName) {
		List<DB> dbs = new ArrayList<DB>();

		if (tableNum == 0) {
			throw new IllegalStateException("table number is 0");
		}

		DB db = null;
		DBClusterInfo dbClusterInfo = this.getDBClusterInfo(clusterName);
		for (DBClusterRegionInfo region : dbClusterInfo.getDbRegions()) {
			int dbIndex = 0;
			for (DBInfo connInfo : region.getMasterConnection()) {
				for (int tableIndex = 0; tableIndex < tableNum; tableIndex++) {
					db = new DB();
					db.setClusterName(clusterName);
					db.setDbCluster(this);
					db.setDatasource(connInfo.getDatasource());
					db.setDbIndex(dbIndex);
					db.setEnd(region.getEnd());
					db.setStart(region.getStart());
					db.setTableName(tableName);
					db.setTableIndex(tableIndex);
					dbs.add(db);
				}
				dbIndex++;
			}
		}

		return dbs;
	}

	@Override
	public List<DB> getAllMasterShardingDB(Class<?> clazz) {
		int tableNum = ReflectUtil.getTableNum(clazz);
		if (tableNum == 0) {
			throw new IllegalStateException("table number is 0");
		}

		String clusterName = ReflectUtil.getClusterName(clazz);
		String tableName = ReflectUtil.getTableName(clazz);

		return getAllMasterShardingDB(tableNum, clusterName, tableName);
	}

	@Override
	public List<DB> getAllSlaveShardingDB(Class<?> clazz, EnumDBMasterSlave slave) {
		List<DB> dbs = new ArrayList<DB>();

		int tableNum = ReflectUtil.getTableNum(clazz);
		if (tableNum == 0) {
			throw new IllegalStateException("table number is 0");
		}

		DB db = null;
		String clusterName = ReflectUtil.getClusterName(clazz);
		String tableName = ReflectUtil.getTableName(clazz);
		DBClusterInfo dbClusterInfo = this.getDBClusterInfo(clusterName);
		for (DBClusterRegionInfo region : dbClusterInfo.getDbRegions()) {
			int dbIndex = 0;
			for (DBInfo connInfo : region.getSlaveConnection().get(slave.getValue())) {
				for (int tableIndex = 0; tableIndex < tableNum; tableIndex++) {
					db = new DB();
					db.setClusterName(clusterName);
					db.setDbCluster(this);
					db.setDatasource(connInfo.getDatasource());
					db.setDbIndex(dbIndex);
					db.setEnd(region.getEnd());
					db.setStart(region.getStart());
					db.setTableName(tableName);
					db.setTableIndex(tableIndex);
					dbs.add(db);
				}
				dbIndex++;
			}
		}

		return dbs;
	}

	@Override
	public Lock createLock(String lockName) {
		InterProcessMutex curatorLock = new InterProcessMutex(curatorClient, Const.ZK_LOCKS + "/" + lockName);
		return new CuratorDistributeedLock(curatorLock);
	}

	@Override
	public IDataLayerBuilder getDataLayerBuilder() {
		IDataLayerBuilder builder = JdbcDataLayerBuilder.valueOf(this);
        builder.setPrimaryCache(this.primaryCache);
        builder.setSecondCache(this.secondCache);
		return builder;
	}

	@Override
	public void setShardInfoFromZk(boolean value) {
		this.isShardInfoFromZk = value;
	}

	@Override
	public List<DBTable> getDBTableFromZk() {
		List<DBTable> tables = new ArrayList<DBTable>();

		try {
			ZooKeeper zkClient = this.curatorClient.getZookeeperClient().getZooKeeper();

			List<String> zkTableNodes = zkClient.getChildren(Const.ZK_SHARDINGINFO, false);
			byte[] tableData = null;
			for (String zkTableNode : zkTableNodes) {
				tableData = zkClient.getData(Const.ZK_SHARDINGINFO + "/" + zkTableNode, false, null);
				tables.add(IOUtil.getObject(tableData, DBTable.class));
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		return tables;
	}

	@Override
	public List<DBTable> getDBTableFromJvm() {
		// load entity object from mulitple package path.
		List<DBTable> tables = new ArrayList<DBTable>();

		try {
			for (String pkgPath : this.scanPackage.split(","))
				tables.addAll(this.dbGenerator.scanEntity(pkgPath));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		return tables;
	}

	/**
	 * 将表分片信息同步到zookeeper.
	 */
	private void _syncToZookeeper(List<DBTable> tables) throws Exception {
		try {
			ZooKeeper zkClient = this.curatorClient.getZookeeperClient().getZooKeeper();

			Stat stat = zkClient.exists(Const.ZK_SHARDINGINFO, false);
			if (stat == null) {
				// 创建根节点
				zkClient.create(Const.ZK_SHARDINGINFO, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}

			// List<String> toBeCleanInfo =
			// zkClient.getChildren(Const.ZK_SHARDINGINFO, false);

			byte[] tableData = null;
			String tableName = null;
			for (DBTable table : tables) {
				tableData = IOUtil.getBytes(table);
				tableName = table.getName();

				// toBeCleanInfo.remove(tableName);

				String zkTableNode = Const.ZK_SHARDINGINFO + "/" + tableName;
				stat = zkClient.exists(zkTableNode, false);
				if (stat == null) {
					zkClient.create(zkTableNode, tableData, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
				} else {
					zkClient.setData(zkTableNode, tableData, -1);
				}
			}

			// if (toBeCleanInfo != null && !toBeCleanInfo.isEmpty()) {
			// for (String toBeCleanTableName : toBeCleanInfo) {
			// zkClient.delete(Const.ZK_SHARDINGINFO + "/" + toBeCleanTableName,
			// -1);
			// if (LOG.isDebugEnabled()) {
			// LOG.debug("clean expire sharding info " + toBeCleanTableName);
			// }
			// }
			// }
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		LOG.info("sharding info of tables have flushed to zookeeper done.");
	}

	/**
	 * 创建数据库表.
	 * 
	 * @throws
	 * @throws IOException
	 */
	private void _createTable(List<DBTable> tables) throws Exception {
		String clusterName = null;
		for (DBTable table : tables) {
			clusterName = table.getCluster();
			if (table.getShardingNum() > 0) { // 当ShardingNumber大于0时表示分库分表
				// read sharding db info.
				DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
				if (dbClusterInfo == null) {
					throw new DBClusterException("找不到相关的集群信息, clusterName=" + clusterName);
				}

				// 创建主库库表
				for (DBClusterRegionInfo region : dbClusterInfo.getDbRegions()) {
					for (DBInfo dbInfo : region.getMasterConnection()) {
						Connection dbConn = dbInfo.getDatasource().getConnection();
						int tableNum = table.getShardingNum();
						this.dbGenerator.syncTable(dbConn, table, tableNum);
						dbConn.close();
					}
				}

				// 创建从库库表
				for (DBClusterRegionInfo region : dbClusterInfo.getDbRegions()) {
					List<List<DBInfo>> slaveDbs = region.getSlaveConnection();
					for (List<DBInfo> slaveConns : slaveDbs) {
						for (DBInfo dbConnInfo : slaveConns) {
							Connection dbConn = dbConnInfo.getDatasource().getConnection();
							int tableNum = table.getShardingNum();
							this.dbGenerator.syncTable(dbConn, table, tableNum);
							dbConn.close();
						}
					}
				}

			} else { // 当ShardingNumber等于0时表示全局表
				DBClusterInfo dbClusterInfo = this.dbClusterInfo.get(clusterName);
				if (dbClusterInfo == null) {
					throw new DBClusterException("加载集群失败，未知的集群，cluster name=" + clusterName);
				}
				// 全局主库
				DBInfo dbConnInfo = dbClusterInfo.getMasterGlobalDBInfo();
				if (dbConnInfo != null) {
					DataSource globalDs = dbConnInfo.getDatasource();
					if (globalDs != null) {
						Connection conn = globalDs.getConnection();
						this.dbGenerator.syncTable(conn, table);
						conn.close();
					}
				}

				// 全局从库
				List<DBInfo> slaveDbs = dbClusterInfo.getSlaveGlobalDBInfo();
				if (slaveDbs != null && !slaveDbs.isEmpty()) {
					for (DBInfo slaveConnInfo : slaveDbs) {
						Connection conn = slaveConnInfo.getDatasource().getConnection();
						this.dbGenerator.syncTable(conn, table);
						conn.close();
					}
				}

			}

		}
	}

	private void _initDBCluster(Map<String, DBClusterInfo> dbClusterInfo) throws LoadConfigException {
		for (Map.Entry<String, DBClusterInfo> entry : dbClusterInfo.entrySet()) {

			// 初始化全局主库
			DBInfo masterGlobalConnection = entry.getValue().getMasterGlobalDBInfo();
			if (masterGlobalConnection != null)
				buildDataSource(masterGlobalConnection);

			// 初始化全局从库
			List<DBInfo> slaveDbs = entry.getValue().getSlaveGlobalDBInfo();
			if (slaveDbs != null && !slaveDbs.isEmpty()) {
				for (DBInfo slaveGlobalConnection : slaveDbs) {
					buildDataSource(slaveGlobalConnection);
				}
			}

			// 初始化集群
			for (DBClusterRegionInfo regionInfo : entry.getValue().getDbRegions()) {
				// 初始化集群主库
				for (DBInfo masterConnection : regionInfo.getMasterConnection()) {
					buildDataSource(masterConnection);
				}

				// 初始化集群从库
				for (List<DBInfo> slaveConnections : regionInfo.getSlaveConnection()) {
					for (DBInfo slaveConnection : slaveConnections) {
						buildDataSource(slaveConnection);
					}
				}
			}

		}
	}

	/**
	 * build table info by table meta.
	 */
	private Map<String, Integer> _loadTableInfo(String clusterName, List<DBTable> tables) throws DBClusterException {
		Map<String, Integer> tableInfos = new HashMap<String, Integer>(tables.size());

		for (DBTable table : tables) {
			if (table.getCluster().equals(clusterName)) {

				String tableName = table.getName();

				int shardingNum = table.getShardingNum();

				tableInfos.put(tableName, shardingNum);
			}
		}

		if (tableInfos.isEmpty()) {
			throw new DBClusterException("找不到可以创建库表的实体对象, 集群名=" + clusterName);
		}

		return tableInfos;
	}

	@Override
	public IClusterRouter getDBRouter() {
		return dbRouter;
	}

	@Override
	public void setDBRouteAlg(EnumDBRouteAlg routeAlg) {
		this.enumDBRouteAlg = routeAlg;
	}

	@Override
	public EnumDBRouteAlg getDBRouteAlg() {
		return this.enumDBRouteAlg;
	}

	/**
	 * 读取配置.
	 * 
	 * @return 配置信息.
	 */
	private IClusterConfig _getConfig(String xmlFilePath) throws LoadConfigException {
		IClusterConfig config = null;

		if (StringUtils.isBlank(xmlFilePath)) {
			config = XmlClusterConfigImpl.getInstance();
		} else {
			config = XmlClusterConfigImpl.getInstance(xmlFilePath);
		}

		return config;
	}

	/**
	 * 创建数据源连接.
	 */
	public abstract void buildDataSource(DBInfo dbConnInfo) throws LoadConfigException;

	/**
	 * 关闭数据源连接
	 * 
	 * @param dbConnInfo
	 */
	public abstract void closeDataSource(DBInfo dbConnInfo);

	public EnumSyncAction getSyncAction() {
		return syncAction;
	}

	@Override
	public void setSyncAction(EnumSyncAction syncAction) {
		this.syncAction = syncAction;
	}

	@Override
	public IDBGenerator getDBGenerator() {
		return this.dbGenerator;
	}

	@Override
	public IIdGenerator getIdGenerator() {
		return this.idGenerator;
	}

	@Override
	public void setScanPackage(String scanPackage) {
		this.scanPackage = scanPackage;
	}

	/*
	 * public Map<String, Map<Integer, Map<String, Integer>>> getTableCluster()
	 * { return this.tableCluster; }
	 */

	@Override
	public ITableCluster getTableCluster() {
		return this.tableCluster;
	}

	@Override
	public IClusterConfig getClusterConfig() {
		return this.config;
	}

}
