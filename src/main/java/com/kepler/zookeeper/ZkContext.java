package com.kepler.zookeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

import com.kepler.KeplerLocalException;
import com.kepler.admin.status.Status;
import com.kepler.annotation.Internal;
import com.kepler.config.Config;
import com.kepler.config.Profile;
import com.kepler.config.PropertiesUtils;
import com.kepler.host.Host;
import com.kepler.host.HostStatus;
import com.kepler.host.HostsContext;
import com.kepler.host.impl.DefaultHostStatus;
import com.kepler.host.impl.ServerHost;
import com.kepler.host.impl.ServerHost.Builder;
import com.kepler.main.Demotion;
import com.kepler.serial.Serials;
import com.kepler.service.Exported;
import com.kepler.service.ExportedInfo;
import com.kepler.service.Imported;
import com.kepler.service.ImportedListener;
import com.kepler.service.Service;
import com.kepler.service.ServiceInstance;

/**
 * @author zhangjiehao 2015年7月9日
 */
public class ZkContext implements Demotion, Imported, Exported, ExportedInfo, ApplicationListener<ContextRefreshedEvent> {

	/**
	 * 保存配置信息路径, 如果失败是否抛出异常终止发布
	 */
	private static final boolean CONFIG_FROCE = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".config_force", false);

	/**
	 * 保存配置信息路径
	 */
	public static final String CONFIG = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".config", "_configs");

	/**
	 * 保存状态信息路径, 如果失败是否抛出异常终止发布
	 */
	private static final boolean STATUS_FROCE = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".status_force", false);

	/**
	 * 保存状态信息路径
	 */
	public static final String STATUS = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".status", "_status");

	private static final long REFRESH_INTERVAL = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".refresh", 10 * 1000);

	private static final int INTERVAL = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".interval", 60000);

	private static final int DELAY = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".delay", 30000);

	/**
	 * 保存服务信息路径
	 */
	public static final String ROOT = PropertiesUtils.get(ZkContext.class.getName().toLowerCase() + ".root", "/kepler");

	/**
	 * 是否发布
	 */
	private static final String EXPORT_KEY = ZkContext.class.getName().toLowerCase() + ".export";

	private static final boolean EXPORT_VAL = PropertiesUtils.get(ZkContext.EXPORT_KEY, true);

	/**
	 * 是否导入
	 */
	private static final String IMPORT_KEY = ZkContext.class.getName().toLowerCase() + ".import";

	private static final boolean IMPORT_VAL = PropertiesUtils.get(ZkContext.IMPORT_KEY, true);

	private static final Log LOGGER = LogFactory.getLog(ZkContext.class);

	private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

	/**
	 * 加载失败的服务
	 */
	private final BlockingQueue<Reinstall> uninstalled = new DelayQueue<Reinstall>();

	/**
	 * 已经卸载的服务
	 */
	private final Set<Service> unsubscribe = new CopyOnWriteArraySet<Service>();

	private final ReinstallRunnable reinstall = new ReinstallRunnable();

	private final RefreshRunnable refresh = new RefreshRunnable();

	private final ZkWatcher watcher = new ZkWatcher();

	private final Snapshot snapshot = new Snapshot();

	private final Exports exports = new Exports();

	private final Roadmap road = new Roadmap();

	/**
	 * 用于延迟发布
	 */
	private final Delay delay = new Delay();

	private final ImportedListener listener;

	private final HostsContext hosts;

	private final ServerHost local;

	private final Profile profile;

	private final Serials serials;

	private final Status status;

	private final Config config;

	private final ZkClient zoo;

	volatile private boolean shutdown;

	public ZkContext(ImportedListener listener, HostsContext hosts, ServerHost local, Serials serials, Profile profile, Config config, Status status, ZkClient zoo) {
		super();
		this.zoo = zoo.bind(this);
		this.listener = listener;
		this.profile = profile;
		this.serials = serials;
		this.config = config;
		this.status = status;
		this.local = local;
		this.hosts = hosts;
	}

	/**
	 * For Spring
	 */
	public void init() {
		// 启动ZK同步线程
		this.executor.scheduleAtFixedRate(this.refresh, ZkContext.REFRESH_INTERVAL, ZkContext.REFRESH_INTERVAL, TimeUnit.MILLISECONDS);
		// 启动服务重加载线程
		this.executor.execute(this.reinstall);
	}

	/**
	 * For Spring
	 * 
	 * @throws Exception
	 */
	public void destroy() throws Exception {
		this.shutdown = true;
		// 关闭内部线程
		this.executor.shutdownNow();
		// 注销已发布服务
		this.exports.destroy();
		// 关闭ZK
		this.zoo.close();
	}

	/**
	 * 重新导入服务
	 * 
	 * @throws Exception
	 */
	private void reset4imported() throws Exception {
		for (Service service : this.snapshot.imported) {
			// 获取所有快照依赖并重新导入
			this.subscribe(service);
		}
		ZkContext.LOGGER.info("Reset imported success ...");
	}

	/**
	 * 重新发布服务
	 * 
	 * @throws Exception
	 */
	private void reset4exported() throws Exception {
		// 从快照获取需发布服务
		for (Service service : this.snapshot.exported.keySet()) {
			// 获取所有服务实例并重新发布
			this.export(service, this.snapshot.exported.get(service));
		}
		ZkContext.LOGGER.info("Reset exported success ...");
	}

	/**
	 * 发布Status节点
	 * 
	 * @throws Exception
	 */
	private void status() throws Exception {
		// 开启并尚未注册
		if (this.exports.status()) {
			try {
				this.exports.status(this.zoo.create(this.road.mkdir(new StringBuffer(ZkContext.ROOT).append(ZkContext.STATUS).toString()) + "/" + this.local.sid(), this.serials.def4output().output(new DefaultHostStatus(this.local, this.status.get()), HostStatus.class), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
			} catch (NodeExistsException exception) {
				// 如果强制发布Status则终止发布
				if (ZkContext.STATUS_FROCE) {
					throw exception;
				} else {
					ZkContext.LOGGER.warn("Status node can not create: " + this.local.sid());
				}
			}
		}
	}

	/**
	 * 发布Config节点(并监听)
	 * 
	 * @throws Exception
	 */
	private void config() throws Exception {
		if (this.exports.config()) {
			try {
				this.exports.config(new ConfigWatcher(this.zoo.create(this.road.mkdir(new StringBuffer(ZkContext.ROOT).append(ZkContext.CONFIG).toString()) + "/" + this.local.sid(), this.serials.def4output().output(PropertiesUtils.memory(), Map.class), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)).path());
			} catch (NodeExistsException exception) {
				// 如果强制发布Config则终止发布
				if (ZkContext.CONFIG_FROCE) {
					throw exception;
				} else {
					ZkContext.LOGGER.warn("Config node can not create: " + this.local.sid());
				}
			}
		}
	}

	public void demote() throws Exception {
		// 降级已发布服务
		this.exports.demote();
	}

	/**
	 * 重置/重连
	 * 
	 * @throws Exception
	 */
	public void reset() throws Exception {
		// 注销已发布服务
		this.exports.destroy();
		// 重新发布服务, 重新加载实例, 重新发布Status节点, 重新发布Config节点
		this.reset4exported();
		this.reset4imported();
		this.status();
		this.config();
	}

	@Override
	public List<ServiceInstance> instance() throws Exception {
		List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
		for (List<ZkInstance> each : this.exports.instance.values()) {
			for (ZkInstance instance : each) {
				instances.add(instance.instance());
			}
		}
		return instances;
	}

	@Override
	public List<Service> services() throws Exception {
		return new ArrayList<Service>(this.exports.instance.keySet());
	}

	@Override
	public void subscribe(Service service) throws Exception {
		// 是否加载远程服务
		if (!PropertiesUtils.profile(this.profile.profile(service), ZkContext.IMPORT_KEY, ZkContext.IMPORT_VAL)) {
			ZkContext.LOGGER.warn("Disabled import service: " + service + " ... ");
			return;
		}
		// 移除已卸载服务
		this.unsubscribe.remove(service);
		try {
			// 订阅服务并启动Watcher监听
			this.refresh.running = true;
			if (this.watcher.watch(service, this.road.road(ZkContext.ROOT, service.service(), service.versionAndCatalog()))) {
				// 加入本地快照
				this.snapshot.subscribe(service);
				ZkContext.LOGGER.info("Import service: " + service);
			}
		} finally {
			this.refresh.running = false;
		}
	}

	@Override
	public void unsubscribe(Service service) throws Exception {
		this.unsubscribe.add(service);
		this.snapshot.unsubscribe(service);
	}

	@Override
	public void export(Service service, Object instance) throws Exception {
		this.delay.exported(service, instance);
	}

	public void logout(Service service) throws Exception {
		this.exports.destroy(service);
		ZkContext.LOGGER.info("Logout service: " + service + " ... ");
	}

	private void exported4delay(Service service, Object instance) throws Exception {
		// 是否发布远程服务
		if (!PropertiesUtils.profile(this.profile.profile(service), ZkContext.EXPORT_KEY, ZkContext.EXPORT_VAL)) {
			ZkContext.LOGGER.warn("Disabled export service: " + service + " ... ");
			return;
		}
		// 生成ZK节点(Profile Tag, Priority)
		ZkSerial serial = new ZkSerial(new Builder(this.local).setTag(PropertiesUtils.profile(this.profile.profile(service), Host.TAG_KEY, Host.TAG_VAL)).setPriority(Integer.valueOf(PropertiesUtils.profile(this.profile.profile(service), Host.PRIORITY_KEY, Host.PRIORITY_DEF))).toServerHost(), service);
		// 加入已导出服务列表
		this.exports.put(this.zoo.create(this.road.mkdir(this.road.road(ZkContext.ROOT, service.service(), service.versionAndCatalog())) + "/", this.serials.def4output().output(serial, ServiceInstance.class), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL), service, serial);
		// 加入已导出快照列表
		this.snapshot.export(service, instance);
		ZkContext.LOGGER.info("Export service: " + service + " ... ");
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		try {
			// 延迟发布
			this.delay.reach();
			// 启动完毕后发布Status/Config节点
			this.status();
			this.config();
		} catch (Throwable throwable) {
			throw new KeplerLocalException(throwable);
		}
	}

	private class Roadmap {

		/**
		 * 以 Content + "/"的形式追加路径
		 * 
		 * @param buffer
		 * @param road
		 * @return
		 */
		private String road(StringBuffer buffer, String... road) {
			for (String each : road) {
				if (StringUtils.hasText(each)) {
					buffer.append(each).append("/");
				}
			}
			return buffer.substring(0, buffer.length() - 1);
		}

		/**
		 * 组合带前缀服务路径
		 * 
		 * @param service
		 * @param road
		 * @return
		 */
		public String road(String prefix, String service, String... road) {
			StringBuffer buffer = new StringBuffer(prefix).append("/").append(service).append("/");
			return this.road(buffer, road);
		}

		/**
		 * 递归创建路径
		 * 
		 * @param road
		 * @return
		 * @throws Exception
		 */
		public String mkdir(String road) throws Exception {
			StringBuffer buffer = new StringBuffer();
			for (String each : road.split("/")) {
				if (StringUtils.hasText(each)) {
					String current = buffer.append("/").append(each).toString();
					if (ZkContext.this.zoo.exists(current, true) == null) {
						ZkContext.this.zoo.create(current, new byte[] {}, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
					}
				}
			}
			return road;
		}
	}

	/**
	 * 已发布服务集合
	 * 
	 * @author KimShen
	 *
	 */
	private class Exports {

		private final Map<Service, List<ZkInstance>> instance = new ConcurrentHashMap<Service, List<ZkInstance>>();

		/**
		 * 已发布服务(Path -> Instance)
		 */
		private final Map<String, ServiceInstance> exported = new ConcurrentHashMap<String, ServiceInstance>();

		/**
		 * 已发布Config路径
		 */
		private String config;

		/**
		 * 已发布Status路径
		 */
		private String status;

		/**
		 * 是否已发布Status
		 * 
		 * @return
		 */
		public boolean status() {
			return this.status == null;
		}

		/**
		 * 是否已发布Config
		 * 
		 * @return
		 */
		public boolean config() {
			return this.config == null;
		}

		/**
		 * 更新Status节点
		 * 
		 * @param status
		 */
		public void status(String status) {
			this.status = status;
		}

		/**
		 * 更新Config节点
		 * 
		 * @param config
		 */
		public void config(String config) {
			this.config = config;
		}

		/**
		 * 已发布服务
		 * 
		 * @param path
		 * @param service
		 * @param instance
		 */
		public void put(String path, Service service, ServiceInstance instance) {
			List<ZkInstance> instances = this.instance.get(service);
			if (instances == null) {
				synchronized (this) {
					// Double check
					if (this.instance.containsKey(service)) {
						instances = this.instance.get(service);
					} else {
						this.instance.put(service, (instances = new CopyOnWriteArrayList<ZkInstance>()));
					}
				}
			}
			instances.add(new ZkInstance(instance, path));
			this.exported.put(path, instance);
			ZkContext.LOGGER.info("[exported-service][service=" + service + "][path=" + path + "]");
		}

		/**
		 * 服务降级, 将指定Path服务降级为优先级0
		 * 
		 * @param path
		 * @param instance
		 */
		private void demote(String path, ServiceInstance instance) {
			try {
				// 修改ZK节点数据
				ZkContext.this.zoo.setData(path, ZkContext.this.serials.def4output().output(new ZkSerial(new Builder(instance.host()).setPriority(0).toServerHost(), instance), ServiceInstance.class), -1);
				ZkContext.LOGGER.info("Demote service: " + instance.host());
			} catch (Exception e) {
				ZkContext.LOGGER.warn(e.getMessage(), e);
			}
		}

		/**
		 * 对所有已发布服务降级
		 * 
		 * @throws Exception
		 */
		public void demote() throws Exception {
			for (String path : this.exported.keySet()) {
				this.demote(path, this.exported.get(path));
			}
		}

		/**
		 * 注销指定服务
		 * 
		 * @param path
		 */
		public void destroy(Service service) {
			List<ZkInstance> instances = this.instance.remove(service);
			if (!instances.isEmpty()) {
				for (ZkInstance instance : instances) {
					this.destroy(instance.path());
				}
			}
		}

		/**
		 * 注销指定ZK节点
		 * 
		 * @param path
		 */
		public void destroy(String path) {
			try {
				if (ZkContext.this.zoo.exists(path, false) != null) {
					ZkContext.this.zoo.delete(path, -1);
					ZkContext.LOGGER.info("[destory][path=" + path + "]");
				}
				// 从已发布服务路径中移除
				this.exported.remove(path);
			} catch (Throwable e) {
				ZkContext.LOGGER.error(e.getMessage(), e);
			}
		}

		/**
		 * 注销Status节点
		 */
		public void destroy4status() {
			if (this.status != null) {
				// 删除ZK节点
				this.destroy(this.status);
				this.status = null;
			}
		}

		/**
		 * 注销Config节点
		 */
		public void destroy4config() {
			if (this.config != null) {
				// 删除ZK节点
				this.destroy(this.config);
				this.config = null;
			}
		}

		/**
		 * 注销已发布服务,已发布Status,已发布Config
		 */
		public void destroy() {
			for (String exported : this.exported.keySet()) {
				this.destroy(exported);
			}
			this.destroy4status();
			this.destroy4config();
		}
	}

	/**
	 * 快照
	 * 
	 * @author kim 2016年1月11日
	 */
	private class Snapshot implements Imported, Exported {

		/**
		 * 已导入实例(多线程竞争)
		 */
		private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<String, ServiceInstance>();

		/**
		 * 已发布服务
		 */
		private final Map<Service, Object> exported = new ConcurrentHashMap<Service, Object>();

		/**
		 * 已导入服务
		 */
		private final Set<Service> imported = new CopyOnWriteArraySet<Service>();

		/**
		 * 获取并移除快照
		 * 
		 * @param path
		 * @return
		 */
		public ServiceInstance instance(String path) {
			return this.instances.remove(path);
		}

		public void instance(String path, ServiceInstance instance) {
			this.instances.put(path, instance);
		}

		@Override
		public void export(Service service, Object instance) throws Exception {
			this.exported.put(service, instance);
		}

		public void logout(Service service) throws Exception {
			this.exported.remove(service);
		}

		@Override
		public void subscribe(Service service) throws Exception {
			this.imported.add(service);
		}

		@Override
		public void unsubscribe(Service service) throws Exception {
			this.imported.remove(service);
		}
	}

	private class ZkWatcher {

		/**
		 * @param service
		 * @param path
		 * @return 是否初始化成功
		 * @throws Exception
		 */
		public boolean watch(Service service, String path) throws Exception {
			try {
				// 获取所有Children Path, 并监听路径变化
				for (String child : new PathWatcher(service, path).snapshot()) {
					this.init(service, path, child);
				}
				return true;
			} catch (Throwable e) {
				// 如果为NoNodeException则进行重试
				if (e.getClass().equals(NoNodeException.class)) {
					this.failedIfInternal(service);
					// 尝试延迟加载
					ZkContext.this.uninstalled.add(new Reinstall(service));
				} else {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
				return false;
			}
		}

		/**
		 * Internal 服务节点处理
		 * 
		 * @param service
		 */
		private void failedIfInternal(Service service) {
			try {
				// 标记为Internal的服务仅提示
				if (AnnotationUtils.findAnnotation(Class.forName(service.service()), Internal.class) != null) {
					ZkContext.LOGGER.info("Instances can not be found for internal service: " + service);
				} else {
					ZkContext.LOGGER.info("Instances can not be found for service: " + service);
				}
			} catch (ClassNotFoundException e) {
				// Generic
				ZkContext.LOGGER.info("Class not found: " + service);
			}
		}

		/**
		 * 初始化已注册服务, 并监听节点变化(节点首次加载)
		 * 
		 * @param path
		 * @param child
		 */
		private void init(Service service, String path, String child) {
			try {
				String actual = path + "/" + child;
				ServiceInstance instance = new DataWatcher(service, actual).snapshot();
				// 加载节点
				ZkContext.this.listener.add(instance);
				// 加载快照
				ZkContext.this.snapshot.instance(actual, instance);
			} catch (Throwable e) {
				ZkContext.LOGGER.info(e.getMessage(), e);
			}
		}
	}

	private class PathWatcher implements Watcher {

		private final Service service;

		private List<String> snapshot;

		private PathWatcher(Service service, String path) throws Exception {
			// 注册路径变化监听
			this.snapshot = ZkContext.this.zoo.getChildren(path, this);
			// 排序用于对比
			Collections.sort(this.snapshot);
			this.service = service;
		}

		/**
		 * 监听路径变化(节点新增)事件
		 * 
		 * @param event
		 */
		private void add(WatchedEvent event) {
			try {
				// Guard case, 已卸载服务
				if (ZkContext.this.unsubscribe.contains(this.service)) {
					ZkContext.LOGGER.warn("[unsubscribed][service=" + this.service + "]");
					return;
				}
				// 获取所有节点,对比新增节点
				List<String> previous = this.snapshot;
				this.snapshot = ZkContext.this.zoo.getChildren(event.getPath(), this);
				Collections.sort(this.snapshot);
				DiffContainer<String> container = new DiffContainer<String>(previous, this.snapshot);
				// 处理新增变化
				this.add(event.getPath(), container.added());
				// 处理删除变化 (FAQ: 与DataWatcher功能相同, 用于本地节点列表与ZK节点的不一致性恢复)
				this.deleted(event.getPath(), container.deleted());
			} catch (Throwable e) {
				throw new KeplerLocalException(e);
			}
		}

		/**
		 * 处理变化后新增节点
		 * 
		 * @param path
		 * @param children
		 */
		private void add(String path, List<String> children) {
			for (String child : children) {
				try {
					String actual = path + "/" + child;
					ServiceInstance instance = new DataWatcher(this.service, actual).snapshot();
					// 加载节点
					ZkContext.this.listener.add(instance);
					// 加载快照
					ZkContext.this.snapshot.instance(actual, instance);
					ZkContext.LOGGER.info("Reconfig and add instance: " + actual + " ( " + instance.host() + ") ");
				} catch (Throwable e) {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
			}
		}

		/**
		 * 处理变化后删除节点
		 * 
		 * @param path
		 * @param children
		 */
		private void deleted(String path, List<String> children) {
			for (String child : children) {
				try {
					String actual = path + "/" + child;
					// 获取并移除快照
					ServiceInstance instance = ZkContext.this.snapshot.instance(actual);
					// 多节点同时上线/下线时可能造成Instance已删除但依然调用Deleted方法
					if (instance != null) {
						ZkContext.this.listener.delete(instance);
						ZkContext.LOGGER.info("Reconfig and delete instance: " + actual + " ( " + instance.host() + ") ");
					}
				} catch (Throwable e) {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
			}
		}

		public List<String> snapshot() {
			return this.snapshot;
		}

		@Override
		public void process(WatchedEvent event) {
			ZkContext.LOGGER.info("Receive event: " + event);
			switch (event.getType()) {
			case NodeChildrenChanged:
				this.add(event);
				return;
			default:
				ZkContext.LOGGER.warn("Can not process event: " + event);
				return;
			}
		}
	}

	private class DataWatcher implements Watcher {

		private final Service service;

		private ServiceInstance data;

		private DataWatcher(Service service, String path) throws Exception {
			// 获取节点数据
			this.data = ZkContext.this.serials.def4input().input(ZkContext.this.zoo.getData(path, this, null), ServiceInstance.class);
			this.service = service;
		}

		public ServiceInstance snapshot() {
			return this.data;
		}

		@Override
		public void process(WatchedEvent event) {
			try {
				// Guard case, 已卸载服务
				if (ZkContext.this.unsubscribe.contains(this.service)) {
					ZkContext.LOGGER.warn("[unsubscribed][service=" + this.service + "]");
					return;
				}
				ZkContext.LOGGER.info("Receive event: " + event);
				switch (event.getType()) {
				case NodeDataChanged:
					ZkContext.this.listener.change(this.data, (this.data = ZkContext.this.serials.def4input().input(ZkContext.this.zoo.getData(event.getPath(), this, null), ServiceInstance.class)));
					return;
				case NodeDeleted:
					ZkContext.this.listener.delete(this.data);
					return;
				default:
					ZkContext.LOGGER.warn("Can not process event: " + event);
					return;
				}
			} catch (Throwable e) {
				throw new KeplerLocalException(e);
			}
		}
	}

	private class ConfigWatcher implements Watcher {

		private final String path;

		private ConfigWatcher(String path) throws Exception {
			// 监听Config节点变化
			ZkContext.this.zoo.exists((this.path = path), this);
		}

		public String path() {
			return this.path;
		}

		@SuppressWarnings("unchecked")
		private ConfigWatcher get(WatchedEvent event) {
			try {
				// Register Watcher for "getData" to avoid "set" failed
				ZkContext.this.config.config(ZkContext.this.serials.def4input().input(ZkContext.this.zoo.getData(event.getPath(), this, null), Map.class));
			} catch (Throwable throwable) {
				ZkContext.LOGGER.error(throwable.getMessage(), throwable);
			}
			return this;
		}

		private ConfigWatcher set() throws Exception {
			// 同步当前Config,保证ZK上节点数据为最新(移除(让Get中的GetDate Watcher失效), 重新发布)
			ZkContext.this.exports.destroy4config();
			ZkContext.this.config();
			return this;
		}

		@Override
		public void process(WatchedEvent event) {
			try {
				ZkContext.LOGGER.info("Receive event: " + event);
				switch (event.getType()) {
				case NodeDataChanged:
					this.get(event).set();
					return;
				case NodeDeleted:
					ZkContext.LOGGER.warn("Config: " + this.path + " will be deleted ... ");
					return;
				default:
					ZkContext.LOGGER.warn("ConfigWatcher can not process event: " + event);
					return;
				}
			} catch (Throwable e) {
				throw new KeplerLocalException(e);
			}
		}
	}

	private class DiffContainer<E extends Comparable<E>> {

		private final List<E> oldList;

		private final List<E> newList;

		private final List<E> elementAdded;

		private final List<E> elementDeleted;

		private DiffContainer(List<E> oldList, List<E> newList) {
			this.oldList = oldList;
			this.newList = newList;
			this.elementAdded = new ArrayList<E>(Math.max(oldList.size(), newList.size()));
			this.elementDeleted = new ArrayList<E>(Math.max(oldList.size(), newList.size()));
			this.calcDiff();
		}

		private void calcDiff() {
			int i = 0, j = 0;
			while (i < this.oldList.size() && j < this.newList.size()) {
				E eleA = this.oldList.get(i);
				E eleB = this.newList.get(j);
				if (eleA.compareTo(eleB) < 0) {
					this.elementDeleted.add(eleA);
					i++;
				} else if (eleA.compareTo(eleB) > 0) {
					this.elementAdded.add(eleB);
					j++;
				} else {
					i++;
					j++;
				}
			}
			for (; i < this.oldList.size(); i++) {
				this.elementDeleted.add(this.oldList.get(i));
			}
			for (; j < this.newList.size(); j++) {
				this.elementAdded.add(this.newList.get(j));
			}
		}

		public List<E> added() {
			return this.elementAdded;
		}

		public List<E> deleted() {
			return this.elementDeleted;
		}
	}

	/**
	 * 服务端口启动成功后, 再发布服务
	 * 
	 * @author tudesheng
	 */
	private class Delay {

		private List<Pair<Service, Object>> services = new ArrayList<Pair<Service, Object>>();

		private boolean started = false;

		public synchronized void exported(Service service, Object instance) throws Exception {
			if (this.started) {
				// 如果已启动则直接发布(场景: 断线重连)
				ZkContext.this.exported4delay(service, instance);
			} else {
				// 未重启则加入缓存
				this.services.add(new Pair<Service, Object>(service, instance));
			}
		}

		/**
		 * 触发延迟加载
		 * 
		 * @throws Exception
		 */
		public synchronized void reach() throws Exception {
			if (!this.started) {
				for (Pair<Service, Object> pair : services) {
					ZkContext.this.exported4delay(pair.key(), pair.val());
				}
				// 切换状态并清空缓存
				started = true;
				services = null;
			}
		}

	}

	private class Pair<K, V> {

		private K key;

		private V val;

		private Pair(K key, V val) {
			this.key = key;
			this.val = val;
		}

		public K key() {
			return key;
		}

		public V val() {
			return val;
		}
	}

	private class Reinstall implements Delayed {

		/**
		 * 当前时间 + delay时间
		 */
		private final long deadline = TimeUnit.MILLISECONDS.convert(ZkContext.DELAY, TimeUnit.MILLISECONDS) + System.currentTimeMillis();

		private final Service service;

		private Reinstall(Service service) {
			super();
			this.service = service;
		}

		public long getDelay(TimeUnit unit) {
			return unit.convert(this.deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		}

		public int compareTo(Delayed o) {
			return this.getDelay(TimeUnit.SECONDS) >= o.getDelay(TimeUnit.SECONDS) ? 1 : -1;
		}

		public Service service() {
			return this.service;
		}

		public String toString() {
			return "[deadline=" + this.deadline + "][service=" + this.service + "]";
		}
	}

	private class ReinstallRunnable implements Runnable {

		@Override
		public void run() {
			while (!ZkContext.this.shutdown) {
				try {
					// 获取未加载服务并尝试重新加载
					Reinstall service = ZkContext.this.uninstalled.poll(ZkContext.INTERVAL, TimeUnit.MILLISECONDS);
					// Guard case1, 无需重加载
					if (service == null) {
						continue;
					}
					// Guard case2, 已卸载服务
					if (ZkContext.this.unsubscribe.contains(service.service())) {
						ZkContext.LOGGER.warn("[unsubscribed][service=" + service.service() + "]");
						continue;
					}
					if (service != null) {
						ZkContext.this.subscribe(service.service());
					}
				} catch (Throwable e) {
					ZkContext.LOGGER.debug(e.getMessage(), e);
				}
			}
			ZkContext.LOGGER.warn("ZkContext shutdown ... ");
		}
	}

	private class RefreshRunnable implements Runnable {

		private volatile boolean running = false;

		@Override
		public void run() {
			if (this.running) {
				return;
			}
			try {
				this.running = true;
				Map<String, ServiceInstance> current = new HashMap<String, ServiceInstance>();
				Set<Service> imported = ZkContext.this.snapshot.imported;
				for (Service service : imported) {
					String path = ZkContext.this.road.road(ZkContext.ROOT, service.service(), service.versionAndCatalog());
					List<String> nodes = ZkContext.this.zoo.getChildren(path, null);
					for (String node : nodes) {
						try {
							byte[] data = ZkContext.this.zoo.getData(path + "/" + node, false, null);
							ServiceInstance instance = ZkContext.this.serials.def4input().input(data, ServiceInstance.class);
							current.put(path + "/" + node, instance);
						} catch (NodeExistsException e) {
							ZkContext.LOGGER.warn("Concurrent case. Node not exists");
						}
					}
				}
				this.handle(current, ZkContext.this.snapshot.instances);
			} catch (Exception e) {
				ZkContext.LOGGER.error(e.getMessage(), e);
			} finally {
				this.running = false;
			}
		}

		private void handle(Map<String, ServiceInstance> current, Map<String, ServiceInstance> snapshot) throws Exception {
			List<ServiceInstance[]> update = new ArrayList<ServiceInstance[]>();
			List<ServiceInstance> install = new ArrayList<ServiceInstance>();
			List<ServiceInstance> remove = new ArrayList<ServiceInstance>();
			for (String each : current.keySet()) {
				// 是否在Instance缓存中
				boolean in_host = ZkContext.this.hosts.getOrCreate(new Service(current.get(each))).contain(current.get(each).host());
				// 是否在Snapshot缓存中
				boolean in_snap = snapshot.containsKey(each);
				if (!in_snap || !in_host) {
					ZkContext.LOGGER.warn("[node pre-install][in-host=" + in_host + "][in-snap=" + in_snap + "][instance=" + current.get(each) + "]");
					install.add(current.get(each));
				} else {
					if (current.get(each).host().propertyChanged(snapshot.get(each).host())) {
						ZkContext.LOGGER.warn("[node pre-update][instance=" + current.get(each) + "]");
						update.add(new ServiceInstance[] { snapshot.get(each), current.get(each) });
					}
				}
			}
			// 移除节点
			for (String each : snapshot.keySet()) {
				if (!current.containsKey(each)) {
					ZkContext.LOGGER.warn("[node pre-delete][instance=" + current.get(each) + "]");
					remove.add(snapshot.get(each));
				}
			}
			ZkContext.LOGGER.info("[refresh][install=" + install.size() + "][update=" + update.size() + "][remove=" + remove.size() + "]");
			this.handleUpdate(update);
			this.handleRemove(remove);
			this.handleInstall(install);
		}

		private void handleInstall(List<ServiceInstance> added) throws Exception {
			for (ServiceInstance instance : added) {
				try {
					ZkContext.LOGGER.warn("[node install]" + instance);
					ZkContext.this.listener.add(instance);
				} catch (Exception e) {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
			}
		}

		private void handleRemove(List<ServiceInstance> removed) throws Exception {
			for (ServiceInstance instance : removed) {
				try {
					ZkContext.LOGGER.warn("[node removed]" + instance);
					ZkContext.this.listener.delete(instance);
				} catch (Exception e) {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
			}
		}

		private void handleUpdate(List<ServiceInstance[]> modified) throws Exception {
			for (ServiceInstance[] instance : modified) {
				try {
					ZkContext.LOGGER.warn("[node update]" + instance[0]);
					ServiceInstance instance_old = instance[0], instance_new = instance[1];
					ZkContext.this.listener.change(instance_old, instance_new);
				} catch (Exception e) {
					ZkContext.LOGGER.error(e.getMessage(), e);
				}
			}
		}
	}
}
