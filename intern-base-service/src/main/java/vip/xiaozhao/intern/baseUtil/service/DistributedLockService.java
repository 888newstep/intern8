package vip.xiaozhao.intern.baseUtil.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁服务（基于Redisson）
 * 解决：多人同时发布动态/点赞/评论等场景下的并发冲突
 * 原理：Redisson RLock 基于 Redis 哨兵/集群的 Lua 脚本实现，自动续期防死锁
 */
@Service
public class DistributedLockService {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockService.class);

    /** 默认锁等待时间：3秒 */
    private static final long DEFAULT_WAIT_TIME = 3L;

    /** 默认锁持有时间：10秒（Redisson自动续期，实际不会超时） */
    private static final long DEFAULT_LEASE_TIME = 10L;

    // ======================= 锁前缀 =======================
    private static final String LOCK_PREFIX = "lock:";
    /** 动态发布锁 */
    public static final String LOCK_DYNAMIC_PUBLISH = LOCK_PREFIX + "dynamic:publish:";
    /** 动态点赞锁 */
    public static final String LOCK_DYNAMIC_LIKE = LOCK_PREFIX + "dynamic:like:";
    /** 动态评论锁 */
    public static final String LOCK_DYNAMIC_COMMENT = LOCK_PREFIX + "dynamic:comment:";
    /** 关注操作锁 */
    public static final String LOCK_FOLLOW = LOCK_PREFIX + "follow:";

    private final RedissonClient redissonClient;

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取分布式锁（带默认超时）
     *
     * @param lockKey 锁的Key
     * @return 锁实例，获取不到返回null
     */
    public RLock tryLock(String lockKey) {
        return tryLock(lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    /**
     * 尝试获取分布式锁
     *
     * @param lockKey   锁的Key
     * @param waitTime  等待锁的最长时间（秒）
     * @param leaseTime 锁持有时间（秒，Redisson自动续期）
     * @return 锁实例，获取不到返回null
     */
    public RLock tryLock(String lockKey, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (acquired) {
                logger.debug("Distributed lock acquired: {}", lockKey);
                return lock;
            } else {
                logger.warn("Failed to acquire distributed lock: {}", lockKey);
                return null;
            }
        } catch (InterruptedException e) {
            logger.error("Distributed lock interrupted: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 释放分布式锁
     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                logger.debug("Distributed lock released");
            } catch (Exception e) {
                logger.error("Failed to release distributed lock", e);
            }
        }
    }

    /**
     * 带锁执行任务
     *
     * @param lockKey  锁的Key
     * @param task     需要执行的任务
     * @param <T>      返回值类型
     * @return 任务执行结果，获取锁失败返回null
     */
    public <T> T executeWithLock(String lockKey, LockTask<T> task) {
        RLock lock = tryLock(lockKey);
        if (lock == null) {
            return null;
        }
        try {
            return task.execute();
        } finally {
            unlock(lock);
        }
    }

    /**
     * 带锁执行任务（无返回值）
     */
    public boolean executeWithLockVoid(String lockKey, Runnable task) {
        RLock lock = tryLock(lockKey);
        if (lock == null) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            unlock(lock);
        }
    }

    @FunctionalInterface
    public interface LockTask<T> {
        T execute();
    }
}