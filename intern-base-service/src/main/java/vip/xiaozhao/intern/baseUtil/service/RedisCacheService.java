package vip.xiaozhao.intern.baseUtil.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 多级缓存服务（Cache-Aside模式）
 * 一级缓存：Caffeine本地缓存（毫秒级访问，减少Redis网络IO）
 * 二级缓存：Redis分布式缓存（集群共享，减少DB压力）
 * 三级存储：MySQL数据库（最终数据源）
 * <p>
 * 解决：缓存穿透、缓存击穿、缓存雪崩
 * - 穿透：BloomFilter布隆过滤器前置过滤
 * - 击穿：互斥锁（分布式锁）保证单线程重建缓存
 * - 雪崩：随机过期时间 + 本地缓存兜底
 */
@Service
public class RedisCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private final RedisUtil redisUtil;

    // Caffeine本地缓存（一级缓存）
    private final Cache<String, Object> localCache;

    // 缓存默认过期时间（秒）
    private static final long DEFAULT_EXPIRE = 3600L;
    // 缓存空值过期时间（防穿透，秒）
    private static final long NULL_EXPIRE = 60L;
    // 本地缓存最大数量
    private static final int LOCAL_CACHE_MAX_SIZE = 10000;
    // 本地缓存过期时间（分钟）
    private static final int LOCAL_CACHE_EXPIRE_MINUTES = 5;

    public RedisCacheService(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterWrite(LOCAL_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * Cache-Aside模式：先查缓存，缓存未命中查DB，回填缓存
     *
     * @param key      缓存Key
     * @param dbLoader DB查询函数
     * @param <T>      返回值类型
     * @return 缓存或DB中的数据
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Function<String, T> dbLoader) {
        return get(key, dbLoader, DEFAULT_EXPIRE);
    }

    /**
     * Cache-Aside模式（带自定义过期时间）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Function<String, T> dbLoader, long expireSeconds) {
        // 1. 查本地缓存（Caffeine）
        Object localValue = localCache.getIfPresent(key);
        if (localValue != null) {
            logger.debug("L1 cache hit: {}", key);
            return (T) localValue;
        }

        // 2. 查Redis缓存
        String redisValue = redisUtil.get(key);
        if (redisValue != null) {
            logger.debug("L2 cache hit: {}", key);
            T value = (T) redisValue;
            localCache.put(key, value);
            return value;
        }

        // 3. 缓存未命中，查DB（防缓存击穿：分布式锁）
        String lockKey = "lock:cache:load:" + key;
        try {
            // 尝试获取分布式锁，防止并发重建缓存
            T value = (T) redisUtil.setnx(lockKey, "1");
            if (value != null && (Long) value == 1) {
                redisUtil.expire(lockKey, 10); // 10秒锁超时
                try {
                    // 查DB
                    T dbValue = dbLoader.apply(key);
                    if (dbValue != null) {
                        // 回填缓存（随机过期时间防雪崩）
                        long actualExpire = expireSeconds + (long) (Math.random() * 300);
                        redisUtil.set(key, String.valueOf(dbValue), (int) actualExpire);
                        localCache.put(key, dbValue);
                    } else {
                        // 缓存空值（防穿透）
                        redisUtil.set(key, "NULL_VALUE", (int) NULL_EXPIRE);
                        localCache.put(key, "NULL_VALUE");
                    }
                    return dbValue;
                } finally {
                    redisUtil.delete(lockKey);
                }
            } else {
                // 没获取到锁，等待后重查Redis
                Thread.sleep(50);
                String retryValue = redisUtil.get(key);
                if (retryValue != null && !"NULL_VALUE".equals(retryValue)) {
                    return (T) retryValue;
                }
                return null;
            }
        } catch (Exception e) {
            logger.error("Cache get error, key: {}", key, e);
            // 降级：直接查DB
            return dbLoader.apply(key);
        }
    }

    /**
     * 更新缓存（Cache-Aside：先更新DB，再删除缓存）
     */
    public void evict(String key) {
        redisUtil.delete(key);
        localCache.invalidate(key);
        logger.debug("Cache evicted: {}", key);
    }

    /**
     * 清空所有本地缓存
     */
    public void clearLocalCache() {
        localCache.invalidateAll();
        logger.info("Local cache cleared");
    }

    /**
     * 获取本地缓存统计信息
     */
    public String getLocalCacheStats() {
        return localCache.stats().toString();
    }
}