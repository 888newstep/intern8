package vip.xiaozhao.intern.baseUtil.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaozhao.intern.baseUtil.service.RedisCacheService;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.time.Duration;
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

class RedisCacheServiceRedisIT extends AbstractRedisContainerIT {

    private static final String CACHE_KEY = "it:cache:load:" + UUID.randomUUID();
    private static final String LOCK_KEY = "lock:cache:load:" + CACHE_KEY;

    private static RedissonRedisCommandClientAdapter redisClient;
    private static RedisUtil redisUtil;
    private static RedisCacheService cacheService;

    @BeforeAll
    static void initializeRedisCacheService() {
        redisClient = RedissonRedisCommandClientAdapter.connect(redisAddress());
        redisUtil = new RedisUtil(redisClient);
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
        }
    }

    @Test
    void slowConcurrentLoaderShouldExecuteOnlyOnce() throws Exception {
        int requestCount = 128;
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<String>> futures = new ArrayList<>(requestCount);

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cacheService.get(CACHE_KEY, String.class, () -> {
                        loaderCalls.incrementAndGet();
                        sleep(250L);
                        return "redis-cache-value";
                    }, 60L);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            for (Future<String> future : futures) {
                assertEquals("redis-cache-value", future.get(8, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, loaderCalls.get());
    }

    @Test
    void wrongOwnerTokenShouldNotReleaseRedisLock() {
        redisClient.set(LOCK_KEY, "owner-a", 30);

        ReflectionTestUtils.invokeMethod(cacheService,
                "releaseLockViaLua", LOCK_KEY, "owner-b");

        assertEquals("owner-a", redisClient.get(LOCK_KEY));

        ReflectionTestUtils.invokeMethod(cacheService,
                "releaseLockViaLua", LOCK_KEY, "owner-a");

        assertNull(redisClient.get(LOCK_KEY));
    }

    @Test
    void redisScriptFailureShouldBypassLockWithoutRetryLoop() {
        redisClient.hset(LOCK_KEY, "field", "wrong-type");
        AtomicInteger loaderCalls = new AtomicInteger();
        long startNanos = System.nanoTime();

        String value = cacheService.get(CACHE_KEY, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "fallback-value";
        }, 60L);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertEquals("fallback-value", value);
        assertEquals(1, loaderCalls.get());
        assertTrue(elapsedMillis < 2000L,
                () -> "Redis script failure caused excessive wait: " + elapsedMillis + " ms");
    }

    @Test
    void expiredLeaseShouldAllowAnotherLoaderToAcquireLock() {
        redisClient.set(LOCK_KEY, "foreign-owner", 1);
        AtomicInteger loaderCalls = new AtomicInteger();
        long startNanos = System.nanoTime();

        String value = cacheService.get(CACHE_KEY, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "lease-recovered-value";
        }, 60L);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertEquals("lease-recovered-value", value);
        assertEquals(1, loaderCalls.get());
        assertTrue(elapsedMillis < 5000L,
                () -> "Lease recovery exceeded bounded wait: " + elapsedMillis + " ms");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Redis cache integration test", exception);
        }
    }
}
