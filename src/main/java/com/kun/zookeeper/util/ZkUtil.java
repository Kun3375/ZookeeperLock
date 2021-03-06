package com.kun.zookeeper.util;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author CaoZiye
 * @version 1.0 2017/11/22 22:00
 */
public class ZkUtil {
    
    private static final Logger log = LoggerFactory.getLogger(ZkUtil.class);
    
    private static ZooKeeper keeper;
    private static final String ADDRESS = "127.0.0.1";
    private static final int ZK_SESSION_TIMEOUT = 30 * 1000;
    private static final String INIT_NODE = "/service";
    private static final String PATH_PREFIX = INIT_NODE + "/";
    private static final String PATH_SUFFIX = "/lock";
    private static final int RETRY_TIMES_FOR_MONITOR = 3;
    private static final long DEFAULT_LOCK_TIMEOUT = 15L * 1000L;
    
    private static ThreadLocal<Map<String, String>> threadLocal
            = ThreadLocal.withInitial(() -> new HashMap<String, String>(4));
    
    /*
     * 初始化zk客户端（zk客户端线程安全，使用同一实例即可）(可交由容器管理)
     */
    static {
        try {
            keeper = new ZooKeeper(ADDRESS, ZK_SESSION_TIMEOUT, event -> log.debug(event.toString()));
            synchronized (ZkUtil.class) {
                if (keeper.exists(INIT_NODE, false) == null) {
                    keeper.create(INIT_NODE, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
            }
        } catch (Exception e1) {
            try {
                if (keeper.exists(INIT_NODE, false) == null) {
                    throw new RuntimeException("ZooKeeper connect failed");
                }
            } catch (InterruptedException | KeeperException e2) {
                throw new RuntimeException("ZooKeeper connect failed");
            }
        }
    }
    
    /**
     * 基本的锁排序对多个对象加锁
     *
     * @param ids 加锁id
     */
    public static void locksAcquire(String... ids) {
        if (ids.length == 0) {
            throw new RuntimeException("have no id to lock ...");
        }
        Arrays.stream(ids)
                .distinct()
                .filter(Objects::nonNull)
                .sorted()
                .forEach(ZkUtil::lockAcquire);
    }
    
    /**
     * 对基本的锁排序加锁对象进行解锁，{@link ZkUtil#locksAcquire(String...)}
     * 不能对自行实现的排序加锁方案进行解锁
     *
     * @param ids 加锁id
     */
    public static void locksRelease(String... ids) {
        if (ids.length == 0) {
            throw new RuntimeException("have no id to release ...");
        }
        Arrays.stream(ids)
                .distinct()
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .forEach(ZkUtil::lockRelease);
    }
    
    /**
     * 上锁方法，使用默认超时时间。超时抛出{@code RuntimeException}
     * @param id 加锁对象id
     */
    public static void lockAcquire(String id) {
        lockAcquire(id, DEFAULT_LOCK_TIMEOUT);
    }
    
    /**
     * 上锁方法
     *
     * @param id 加锁对象id
     * @param timeout 超时时间
     */
    public static void lockAcquire(String id, long timeout) {
        if (timeout <= 0) {
            throw new RuntimeException("lock failed, illegal timeout value");
        }
        try {
            // 创建userId父节点
            Stat stat = keeper.exists(PATH_PREFIX + id, false);
            if (stat == null) {
                try {
                    String parentNodePath = keeper.create(
                            PATH_PREFIX + id,
                            "".getBytes(),
                            ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                    log.debug("create parent node {}", parentNodePath);
                } catch (Exception e) {
                    // it is OK, don't mind it
                    log.debug("lock parent node {} is already exists", PATH_PREFIX + id);
                }
            }
            // 创建临时顺序节点
            String nodePath;
            try {
                nodePath = keeper.create(
                        PATH_PREFIX + id + PATH_SUFFIX,
                        "".getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL_SEQUENTIAL);
                log.debug("create node {}", nodePath);
            } catch (KeeperException e) {
                // 父节点删除情况 尝试重建
                lockAcquire(id, timeout);
                return;
            }

            String nodePathName = nodePath.substring((PATH_PREFIX + id).length() + 1);
            // 便于解锁，使用MAP因为一个线程可能使用多个锁
            threadLocal.get().put(id, nodePathName);
            // 上锁过程
            lock(id, nodePathName, timeout, 0);
        } catch (Exception e) {
            log.warn("lock failed...", e);
            throw new RuntimeException("lock failed", e);
        }
    }
    
    /**
     * 加锁核心方法
     *
     * @param id           加锁对象id
     * @param nodePathName 加锁时预创建的临时顺序节点名称
     * @param failCount    失败尝试计数
     * @throws KeeperException
     * @throws InterruptedException
     */
    private static boolean lock(String id, String nodePathName, long timeout, int failCount)
            throws KeeperException, InterruptedException {
        // 获取子节点
        List<String> children = keeper.getChildren(PATH_PREFIX + id, null);
        for (String child : children) {
            log.debug("child node {}", child);
        }
        // 临时节点排序
        Collections.sort(children);
        try {
            for (int i = 0; i < children.size(); i++) {
                // 视为获得锁
                if (i == 0 && nodePathName.equals(children.get(i))) {
                    log.debug("gain the lock");
                    return true;
                }
                // 等待锁，监视之前的节点
                if (nodePathName.equals(children.get(i))) {
                    // 未获得锁时设置闸
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    
                    log.debug("monitor node {}", PATH_PREFIX + id + "/" + children.get(i - 1));
                    keeper.getData(PATH_PREFIX + id + "/" + children.get(i - 1),
                            event -> {
                                if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                                    log.debug("previous node deleting event has been detected");
                                    countDownLatch.countDown();
                                }
                            }, new Stat());
                    if (countDownLatch.await(timeout, TimeUnit.MILLISECONDS)) {
                        log.debug("gain the lock");
                        return true;
                    }
                    throw new RuntimeException("try lock timeout ...");
                }
            }
            log.info("my sequence node:{} maybe deleted ...", PATH_PREFIX + id + "/" + nodePathName);
            throw new RuntimeException("lock failed");
        } catch (KeeperException | InterruptedException e) {
            // 可能在监视前一节点时失败
            log.debug("watch failed, will try again...");
            if (++failCount <= RETRY_TIMES_FOR_MONITOR) {
                return lock(id, nodePathName, timeout, failCount);
            }
            throw new RuntimeException("watch failed, will not tray any more", e);
        }
        
    }
    
    /**
     * 解锁方法
     *
     * @param id 加锁对象id
     */
    public static void lockRelease(String id) {
        try {
            Map<String, String> lockMap = threadLocal.get();
            String nodePathName = PATH_PREFIX + id + "/" + lockMap.remove(id);
            keeper.delete(nodePathName, -1);
            log.debug("node {} deleted, lock release", nodePathName);
            // 这样做会使得线程每次上锁都会创建新的MAP，
            // 但是不使用remove方法，在web环境下（SpringBoot无所谓吧）会导致内存泄漏或伪内存泄漏
            // 尽管tomcat有预防此类问题的自动防御机制，但是为了保证代码的通用性和风格，退化threadLocal
            if (lockMap.isEmpty()) {
                threadLocal.remove();
            }
            // 删除遗留的节点，大部分情况下会出现异常（无影响）
            // 原则上本应该避免，但是我们仍旧需要清理多余的节点
            keeper.delete(PATH_PREFIX + id, -1);
        } catch (InterruptedException | KeeperException e) {
            log.debug(e.getMessage());
            return;
        }
        log.debug("all lock about key={} released, deleted parent node", id);
    }
    
    /**
     * zk客户端关闭（可交给容器控制）
     */
    public static void close() {
        try {
            if (keeper != null) {
                keeper.close();
            }
        } catch (InterruptedException e) {
            log.debug("zk close fail", e);
        }
    }
    
}
