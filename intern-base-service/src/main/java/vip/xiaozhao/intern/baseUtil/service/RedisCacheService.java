package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Objects;
import java.util.UUID;
import vip.xiaozhao.intern.baseUtil.config.CacheSingleFlightProperties;

/**
 * Multi-level Cache-Aside pattern.
 * L1: Caffeine (in-process)
 * L2: Redis
 * L3: MySQL
 *
 * Cache loading lock uses a Lua script to guarantee atomicity of setnx+expire,
 * preventing lock leaks when the process crashes between the two calls.
 */
@Service
public class RedisCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String NULL_VALUE = "__NULL__";
    private static final String LOCK_PREFIX = "lock:cache:load:";

    // Lua script: atomically setnx + expire. Returns 1 if lock acquired, 0 if already held.
    // KEYS[1] = lock key, ARGV[1] = lock value, ARGV[2] = expire seconds
    private static final String LOCK_LUA_SCRIPT =
            "if redis.call('setnx', KEYS[1], ARGV[1]) == 1 then " +
            "  redis.call('expire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    // Only the owner that supplied the token may release the lock.
    private static final String LOCK_RELEASE_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private static final long DEFAULT_EXPIRE = 3600L;
    private static final long NULL_EXPIRE = 60L;
    private static final int LOCAL_CACHE_MAX_SIZE = 10000;
    private static final int LOCAL_CACHE_EXPIRE_MINUTES = 5;
    private static final int LOCK_EXPIRE_SECONDS = 10;
    private static final int MAX_RETRY_WAIT_MS = 100;
    private static final int INITIAL_RETRY_WAIT_MS = 10;

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;
    private final Cache<Object, Object> localCache;
    private final CircuitBreakerService circuitBreakerService;
    private final long singleFlightWaitTimeoutMs;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> localInFlightLoads =
            new ConcurrentHashMap<>();
    private final Timer cacheLookupTimer;
    private final Timer cacheDbLoadTimer;
    private final Counter localHitCounter;
    private final Counter redisHitCounter;
    private final Counter cacheMissCounter;
    private final Counter dbLoadCounter;
    private final Counter evictCounter;
    private final Counter lockContentionCounter;
    private final Counter singleFlightLeaderCounter;
    private final Counter singleFlightWaiterCounter;
    private final Counter singleFlightFailureCounter;
    private final Counter singleFlightTimeoutCounter;

    private final LongAdder localHitCount = new LongAdder();
    private final LongAdder redisHitCount = new LongAdder();
    private final LongAdder cacheMissCount = new LongAdder();
    private final LongAdder dbLoadCount = new LongAdder();

    public RedisCacheService(RedisUtil redisUtil, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this(redisUtil, objectMapper, meterRegistry, createLocalCache(),
                new CircuitBreakerService(meterRegistry), CacheSingleFlightProperties.DEFAULT_WAIT_TIMEOUT_MS);
    }

    public RedisCacheService(RedisUtil redisUtil, ObjectMapper objectMapper, MeterRegistry meterRegistry,
                             @Qualifier("dynamicLocalCache") Cache<Object, Object> localCache) {
        this(redisUtil, objectMapper, meterRegistry, localCache,
                new CircuitBreakerService(meterRegistry), CacheSingleFlightProperties.DEFAULT_WAIT_TIMEOUT_MS);
    }

    public RedisCacheService(RedisUtil redisUtil, ObjectMapper objectMapper, MeterRegistry meterRegistry,
                             @Qualifier("dynamicLocalCache") Cache<Object, Object> localCache,
                             CircuitBreakerService circuitBreakerService) {
        this(redisUtil, objectMapper, meterRegistry, localCache, circuitBreakerService,
                CacheSingleFlightProperties.DEFAULT_WAIT_TIMEOUT_MS);
    }

    @Autowired
    public RedisCacheService(RedisUtil redisUtil, ObjectMapper objectMapper, MeterRegistry meterRegistry,
                             @Qualifier("dynamicLocalCache") Cache<Object, Object> localCache,
                             CircuitBreakerService circuitBreakerService,
                             CacheSingleFlightProperties singleFlightProperties) {
        this(redisUtil, objectMapper, meterRegistry, localCache, circuitBreakerService,
                singleFlightProperties.getWaitTimeoutMs());
    }

    public RedisCacheService(RedisUtil redisUtil, ObjectMapper objectMapper, MeterRegistry meterRegistry,
                             @Qualifier("dynamicLocalCache") Cache<Object, Object> localCache,
                             CircuitBreakerService circuitBreakerService,
                             long singleFlightWaitTimeoutMs) {
        this.redisUtil = Objects.requireNonNull(redisUtil, "redisUtil must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.localCache = Objects.requireNonNull(localCache, "localCache must not be null");
        this.circuitBreakerService = Objects.requireNonNull(circuitBreakerService,
                "circuitBreakerService must not be null");
        if (singleFlightWaitTimeoutMs <= 0) {
            throw new IllegalArgumentException("singleFlightWaitTimeoutMs must be positive");
        }
        this.singleFlightWaitTimeoutMs = singleFlightWaitTimeoutMs;
        this.cacheLookupTimer = Timer.builder("cache.lookup.duration")
                .description("Total time spent resolving a cache lookup")
                .register(meterRegistry);
        this.cacheDbLoadTimer = Timer.builder("cache.db.load.duration")
                .description("Time spent loading data from the database")
                .register(meterRegistry);
        this.localHitCounter = Counter.builder("cache.hit.local")
                .description("Local cache hit count")
                .register(meterRegistry);
        this.redisHitCounter = Counter.builder("cache.hit.redis")
                .description("Redis cache hit count")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.miss")
                .description("Cache miss count")
                .register(meterRegistry);
        this.dbLoadCounter = Counter.builder("cache.db.load")
                .description("Database load count")
                .register(meterRegistry);
        this.evictCounter = Counter.builder("cache.evict")
                .description("Cache eviction count")
                .register(meterRegistry);
        this.lockContentionCounter = Counter.builder("cache.lock.contention")
                .description("Cache loading lock contention count")
                .register(meterRegistry);
        this.singleFlightLeaderCounter = Counter.builder("cache.singleflight.leader")
                .description("Cache miss loads executed by the local single-flight leader")
                .register(meterRegistry);
        this.singleFlightWaiterCounter = Counter.builder("cache.singleflight.waiter")
                .description("Cache miss loads waiting for a local single-flight leader")
                .register(meterRegistry);
        this.singleFlightFailureCounter = Counter.builder("cache.singleflight.failure")
                .description("Local single-flight loads that failed")
                .register(meterRegistry);
        this.singleFlightTimeoutCounter = Counter.builder("cache.singleflight.timeout")
                .description("Local single-flight waiters that reached the configured timeout")
                .register(meterRegistry);
    }

    private static Cache<Object, Object> createLocalCache() {
        return Caffeine.newBuilder().maximumSize(LOCAL_CACHE_MAX_SIZE).expireAfterWrite(LOCAL_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES).recordStats().build();
    }

    public <T> T get(String key, Class<T> type, Function<String, T> dbLoader) {
        return get(key, type, () -> dbLoader.apply(key), DEFAULT_EXPIRE);
    }

    public <T> T get(String key, Class<T> type, Supplier<T> dbLoader) {
        return get(key, type, dbLoader, DEFAULT_EXPIRE);
    }

    public <T> T get(String key, Class<T> type, Supplier<T> dbLoader, long expireSeconds) {
        Objects.requireNonNull(key, "cache key must not be null");
        Objects.requireNonNull(type, "cache value type must not be null");
        Objects.requireNonNull(dbLoader, "dbLoader must not be null");
        if (expireSeconds <= 0 || expireSeconds > Integer.MAX_VALUE - 300L) {
            throw new IllegalArgumentException("expireSeconds must be in (0, Integer.MAX_VALUE - 300]");
        }
        return cacheLookupTimer.record(() -> doGet(key, type, dbLoader, expireSeconds));
    }

    private <T> T doGet(String key, Class<T> type, Supplier<T> dbLoader, long expireSeconds) {
        // L1: Caffeine local cache
        LocalCacheLookup<T> localLookup = readLocalCacheValue(key, type);
        if (localLookup.hit()) {
            return localLookup.value();
        }

        // L2: Redis
        CacheLookup<T> redisLookup = readRedisValue(key, type);
        if (redisLookup.hit()) {
            redisHitCount.increment();
            redisHitCounter.increment();
            localCache.put(key, redisLookup.value() == null ? NULL_VALUE : redisLookup.value());
            return redisLookup.value();
        }

        // L3: Cache miss -> load from DB with atomic Lua lock
        cacheMissCount.increment();
        cacheMissCounter.increment();
        if (!redisLookup.redisAvailable()) {
            return loadWithLocalSingleFlight(key, type, dbLoader, expireSeconds);
        }

        String lockKey = LOCK_PREFIX + key;

        // Atomic lock acquisition via Lua script (setnx + expire in one round-trip).
        LockAcquireResult lockAttempt = tryAcquireLockViaLua(lockKey);
        if (lockAttempt.token() != null) {
            try {
                return loadAndCache(key, dbLoader, expireSeconds);
            } finally {
                releaseLockViaLua(lockKey, lockAttempt.token());
            }
        }

        if (!lockAttempt.redisAvailable()) {
            logger.warn("Redis cache loading lock unavailable; using local single-flight, key={}", key);
            return loadWithLocalSingleFlight(key, type, dbLoader, expireSeconds);
        }

        // Lock contention: another thread or instance is loading. Poll until the cache is filled or the lease expires.
        lockContentionCounter.increment();
        return waitForReloadOrAcquire(key, type, dbLoader, expireSeconds, lockKey);
    }

    private <T> T loadWithLocalSingleFlight(String key, Class<T> type,
                                             Supplier<T> dbLoader, long expireSeconds) {
        // A request may have missed L1 before the leader completed. Re-check it
        // before creating a new flight to close that race window.
        LocalCacheLookup<T> localLookup = readLocalCacheValue(key, type);
        if (localLookup.hit()) {
            return localLookup.value();
        }

        CompletableFuture<Object> newLoad = new CompletableFuture<>();
        CompletableFuture<Object> existingLoad = localInFlightLoads.putIfAbsent(key, newLoad);
        if (existingLoad != null) {
            singleFlightWaiterCounter.increment();
            return awaitLocalLoad(key, existingLoad, type);
        }

        singleFlightLeaderCounter.increment();
        try {
            T value = loadAndCache(key, dbLoader, expireSeconds);
            newLoad.complete(value);
            return value;
        } catch (RuntimeException | Error exception) {
            singleFlightFailureCounter.increment();
            newLoad.completeExceptionally(exception);
            throw exception;
        } finally {
            localInFlightLoads.remove(key, newLoad);
        }
    }

    private <T> T awaitLocalLoad(String key, CompletableFuture<Object> load, Class<T> type) {
        try {
            Object value = load.get(singleFlightWaitTimeoutMs, TimeUnit.MILLISECONDS);
            return value == null ? null : type.cast(value);
        } catch (TimeoutException exception) {
            singleFlightTimeoutCounter.increment();
            logger.warn("Cache single-flight waiter timed out, keyHash={}, timeoutMs={}",
                    Integer.toHexString(key.hashCode()), singleFlightWaitTimeoutMs);
            throw new CacheSingleFlightTimeoutException(singleFlightWaitTimeoutMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Cache single-flight waiter interrupted, keyHash={}",
                    Integer.toHexString(key.hashCode()));
            throw new CacheSingleFlightTimeoutException("Cache single-flight wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cache single-flight load failed", cause);
        }
    }

    private <T> LocalCacheLookup<T> readLocalCacheValue(String key, Class<T> type) {
        Object localValue = localCache.getIfPresent(key);
        if (localValue == null) {
            return new LocalCacheLookup<>(false, null);
        }

        localHitCount.increment();
        localHitCounter.increment();
        if (NULL_VALUE.equals(localValue)) {
            return new LocalCacheLookup<>(true, null);
        }
        if (type.isInstance(localValue)) {
            return new LocalCacheLookup<>(true, type.cast(localValue));
        }

        localCache.invalidate(key);
        return new LocalCacheLookup<>(false, null);
    }

    /**
     * Atomically acquire a cache loading lock using Lua script.
     * Guarantees setnx + expire execute as a single atomic operation,
     * preventing lock leaks if the process crashes between the two calls.
     */
    private LockAcquireResult tryAcquireLockViaLua(String lockKey) {
        List<String> keys = Arrays.asList(lockKey);
        String lockToken = UUID.randomUUID().toString();
        List<String> args = Arrays.asList(lockToken, String.valueOf(LOCK_EXPIRE_SECONDS));
        return circuitBreakerService.executeWithRedisBreaker(() -> {
            Object result = redisUtil.evalStrict(LOCK_LUA_SCRIPT, keys, args);
            String resultValue = String.valueOf(result);
            if ("1".equals(resultValue)) {
                return new LockAcquireResult(lockToken, true);
            }
            if ("0".equals(resultValue)) {
                return new LockAcquireResult(null, true);
            }
            logger.warn("Redis lock script returned an unexpected result, key={}, result={}",
                    lockKey, result);
            return new LockAcquireResult(null, false);
        }, () -> new LockAcquireResult(null, false));
    }

    private void releaseLockViaLua(String lockKey, String lockToken) {
        circuitBreakerService.executeWithRedisBreaker(() -> {
            redisUtil.evalStrict(LOCK_RELEASE_LUA_SCRIPT,
                    Arrays.asList(lockKey), Arrays.asList(lockToken));
            return Boolean.TRUE;
        }, () -> Boolean.FALSE);
    }

    private <T> T waitForReloadOrAcquire(String key, Class<T> type, Supplier<T> dbLoader,
                                          long expireSeconds, String lockKey) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(LOCK_EXPIRE_SECONDS);
        int waitMs = INITIAL_RETRY_WAIT_MS;

        while (System.nanoTime() < deadline) {
            CacheLookup<T> lookup = readRedisValue(key, type);
            if (lookup.hit()) {
                redisHitCount.increment();
                redisHitCounter.increment();
                localCache.put(key, lookup.value() == null ? NULL_VALUE : lookup.value());
                return lookup.value();
            }
            if (!lookup.redisAvailable()) {
                return loadWithLocalSingleFlight(key, type, dbLoader, expireSeconds);
            }

            LockAcquireResult lockAttempt = tryAcquireLockViaLua(lockKey);
            if (lockAttempt.token() != null) {
                try {
                    return loadAndCache(key, dbLoader, expireSeconds);
                } finally {
                    releaseLockViaLua(lockKey, lockAttempt.token());
                }
            }
            if (!lockAttempt.redisAvailable()) {
                return loadWithLocalSingleFlight(key, type, dbLoader, expireSeconds);
            }

            sleepBeforeRetry(waitMs);
            waitMs = Math.min(waitMs * 2, MAX_RETRY_WAIT_MS);
        }

        logger.warn("Cache loading lease expired without a value; falling back to DB, key={}", key);
        return loadAndCache(key, dbLoader, expireSeconds);
    }

    private void sleepBeforeRetry(int waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for cache reload", exception);
        }
    }

    private <T> CacheLookup<T> readRedisValue(String key, Class<T> type) {
        RedisValueResult redisResult = circuitBreakerService.executeWithRedisBreaker(
                () -> new RedisValueResult(redisUtil.getStrict(key), true),
                () -> new RedisValueResult(null, false));
        if (!redisResult.available()) {
            return new CacheLookup<>(false, null, false);
        }

        String redisValue = redisResult.value();
        if (redisValue == null) {
            return new CacheLookup<>(false, null, true);
        }
        if (NULL_VALUE.equals(redisValue)) {
            return new CacheLookup<>(true, null, true);
        }

        T value = deserialize(redisValue, type);
        if (value != null) {
            return new CacheLookup<>(true, value, true);
        }

        deleteRedisValue(key);
        return new CacheLookup<>(false, null, true);
    }

    private <T> T loadAndCache(String key, Supplier<T> dbLoader, long expireSeconds) {
        dbLoadCount.increment();
        dbLoadCounter.increment();
        T dbValue = cacheDbLoadTimer.record(dbLoader::get);
        storeValue(key, dbValue, expireSeconds);
        return dbValue;
    }

    private <T> void storeValue(String key, T value, long expireSeconds) {
        if (value == null) {
            writeRedisValue(key, NULL_VALUE, (int) NULL_EXPIRE);
            localCache.put(key, NULL_VALUE);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(value);
            // Randomize TTL to prevent cache stampede
            long actualExpire = expireSeconds + ThreadLocalRandom.current().nextLong(300);
            writeRedisValue(key, payload, (int) actualExpire);
            localCache.put(key, value);
        } catch (Exception e) {
            logger.error("Cache serialize error, key: {}", key, e);
        }
    }

    private boolean writeRedisValue(String key, String value, int expireSeconds) {
        return circuitBreakerService.executeWithRedisBreaker(() -> {
            redisUtil.setStrict(key, value, expireSeconds);
            return Boolean.TRUE;
        }, () -> Boolean.FALSE);
    }

    private boolean deleteRedisValue(String key) {
        return circuitBreakerService.executeWithRedisBreaker(() -> {
            redisUtil.deleteStrict(key);
            return Boolean.TRUE;
        }, () -> Boolean.FALSE);
    }

    private record LockAcquireResult(String token, boolean redisAvailable) {
    }

    private record RedisValueResult(String value, boolean available) {
    }

    private record LocalCacheLookup<T>(boolean hit, T value) {
    }

    private record CacheLookup<T>(boolean hit, T value, boolean redisAvailable) {
    }

    private <T> T deserialize(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            logger.error("Cache deserialize error, type: {}", type.getSimpleName(), e);
            return null;
        }
    }

    public void evict(String key) {
        deleteRedisValue(key);
        localCache.invalidate(key);
        evictCounter.increment();
        logger.debug("Cache evicted: {}", key);
    }

    /**
     * Delayed double-delete: evict now, then schedule a second eviction after a delay.
     * This handles the race condition where a stale write occurs between the first
     * delete and the DB update completing.
     */
    public void evictWithDoubleDelete(String key, long delayMs) {
        evict(key);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(delayMs);
                    evict(key);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to schedule delayed cache eviction for key: {}", key, e);
        }
    }

    public void clearLocalCache() {
        localCache.invalidateAll();
        logger.info("Local cache cleared");
    }

    public String getLocalCacheStats() {
        return localCache.stats().toString();
    }

    public long getLocalHitCount() {
        return localHitCount.sum();
    }

    public long getRedisHitCount() {
        return redisHitCount.sum();
    }

    public long getCacheHitCount() {
        return localHitCount.sum() + redisHitCount.sum();
    }

    public long getCacheMissCount() {
        return cacheMissCount.sum();
    }

    public long getDbLoadCount() {
        return dbLoadCount.sum();
    }

    public double getCacheHitRate() {
        long total = getCacheHitCount() + getCacheMissCount();
        return total == 0 ? 0.0 : (double) getCacheHitCount() / total;
    }
}
