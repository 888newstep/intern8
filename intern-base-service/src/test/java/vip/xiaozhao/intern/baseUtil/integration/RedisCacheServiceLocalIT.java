package vip.xiaozhao.intern.baseUtil.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaozhao.intern.baseUtil.service.RedisCacheService;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in integration test for a Redis server installed on the local Windows host.
 *
 * <p>Credentials are read only from {@code REDIS_AUTH} or the JVM property
 * {@code redis.local.it.password}; the test never stores them in the repository.</p>
 */
class RedisCacheServiceLocalIT {

    private static final String CACHE_KEY = "it:local:cache:" + UUID.randomUUID();
    private static final String LOCK_KEY = "lock:cache:load:" + CACHE_KEY;

    private static RedissonRedisCommandClientAdapter redisClient;
    private static RedisCacheService cacheService;

    @BeforeAll
    static void initializeRedisCacheService() {
        boolean enabled = Boolean.parseBoolean(
                System.getProperty("redis.local.it.enabled", "false"));
        Assumptions.assumeTrue(enabled,
                "Local Redis IT disabled; set -Dredis.local.it.enabled=true to run it");

        String host = propertyOrEnvironment("redis.local.it.host", "REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(propertyOrEnvironment("redis.local.it.port", "REDIS_PORT", "6379"));
        String password = propertyOrEnvironment("redis.local.it.password", "REDIS_AUTH", "");
        if (password.isBlank()) {
            throw new IllegalStateException(
                    "Local Redis IT requires REDIS_AUTH or redis.local.it.password");
        }

        redisClient = RedissonRedisCommandClientAdapter.connect(
                "redis://" + host + ":" + port, password);
        try {
            redisClient.delete(CACHE_KEY);
            redisClient.delete(LOCK_KEY);
        } catch (RuntimeException exception) {
            closeRedisClient();
            throw new IllegalStateException("Local Redis is unreachable or credentials are invalid", exception);
        }

        RedisUtil redisUtil = new RedisUtil(redisClient);
        Cache<Object, Object> localCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .build();
        cacheService = new RedisCacheService(
                redisUtil,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                localCache);
    }

    @BeforeEach
    void clearTestKeys() {
        cacheService.clearLocalCache();
        redisClient.delete(CACHE_KEY);
        redisClient.delete(LOCK_KEY);
    }

    @AfterAll
    static void closeRedisClient() {
        if (redisClient != null) {
            redisClient.close();
            redisClient = null;
        }
    }

    @Test
    void realRedisShouldReuseStoredValueAfterL1Eviction() {
        AtomicInteger loaderCalls = new AtomicInteger();

        assertEquals("redis-local-value", cacheService.get(CACHE_KEY, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "redis-local-value";
        }, 60L));

        cacheService.clearLocalCache();
        assertEquals("redis-local-value", cacheService.get(CACHE_KEY, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "unexpected-second-loader";
        }, 60L));
        assertEquals(1, loaderCalls.get());
    }

    @Test
    void realRedisWrongOwnerTokenShouldNotReleaseLock() {
        redisClient.set(LOCK_KEY, "owner-a", 30);

        ReflectionTestUtils.invokeMethod(cacheService,
                "releaseLockViaLua", LOCK_KEY, "owner-b");

        assertEquals("owner-a", redisClient.get(LOCK_KEY));

        ReflectionTestUtils.invokeMethod(cacheService,
                "releaseLockViaLua", LOCK_KEY, "owner-a");

        assertNull(redisClient.get(LOCK_KEY));
    }

    @Test
    void realRedisConcurrentLoaderShouldExecuteOnlyOnce() throws Exception {
        int requestCount = 128;
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<String>> futures = new ArrayList<>(requestCount);

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cacheService.get(CACHE_KEY, String.class, () -> {
                        loaderCalls.incrementAndGet();
                        sleep(250L);
                        return "redis-concurrent-value";
                    }, 60L);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("redis-concurrent-value", future.get(8, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, loaderCalls.get());
    }

    private static String propertyOrEnvironment(String propertyName,
                                                 String environmentName,
                                                 String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank()
                ? defaultValue
                : environmentValue;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during local Redis integration test", exception);
        }
    }
}
