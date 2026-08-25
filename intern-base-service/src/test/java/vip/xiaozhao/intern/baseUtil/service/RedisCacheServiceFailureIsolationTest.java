package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.config.CircuitBreakerProperties;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceFailureIsolationTest {

    private static final String CACHE_KEY = "test:redis-failure-single-flight";

    @Test
    void acquiredLockShouldRecheckRedisBeforeLoadingDatabase() {
        String cacheKey = CACHE_KEY + ":lock-recheck";
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getStrict(cacheKey))
                .thenReturn(null, "\"concurrent-value\"");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList()))
                .thenReturn(1L);

        RedisCacheService cacheService = new RedisCacheService(
                redisUtil,
                new ObjectMapper(),
                meterRegistry,
                Caffeine.newBuilder().maximumSize(100).build());
        AtomicInteger loaderCalls = new AtomicInteger();

        String value = cacheService.get(cacheKey, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "unexpected-loader-value";
        }, 60L);

        assertEquals("concurrent-value", value);
        assertEquals(0, loaderCalls.get());
        verify(redisUtil, never()).setStrict(anyString(), anyString(), anyInt());
    }

    @Test
    void redisReadFailureShouldSingleFlightDatabaseLoadAndOpenBreaker() throws Exception {
        assertRedisFailureSingleFlight(CACHE_KEY + ":read", true);
    }

    @Test
    void redisScriptFailureShouldSingleFlightDatabaseLoadAndOpenBreaker() throws Exception {
        assertRedisFailureSingleFlight(CACHE_KEY + ":script", false);
    }

    @Test
    void slowLeaderShouldBoundWaiterWithoutStartingSecondDatabaseLoad() throws Exception {
        String cacheKey = CACHE_KEY + ":wait-timeout";
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService(
                meterRegistry, testProperties());
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getStrict(cacheKey))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        Cache<Object, Object> localCache = Caffeine.newBuilder()
                .maximumSize(100)
                .recordStats()
                .build();
        RedisCacheService cacheService = new RedisCacheService(
                redisUtil,
                new ObjectMapper(),
                meterRegistry,
                localCache,
                circuitBreakerService,
                100L);

        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loaderCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> leader = executor.submit(() -> cacheService.get(cacheKey, String.class, () -> {
                loaderCalls.incrementAndGet();
                loaderStarted.countDown();
                try {
                    assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while releasing test loader", exception);
                }
                return "slow-leader-value";
            }, 60L));

            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            Future<String> waiter = executor.submit(() -> cacheService.get(
                    cacheKey, String.class, () -> "unexpected-second-loader", 60L));

            ExecutionException waiterFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> waiter.get(2, TimeUnit.SECONDS));
            assertTrue(waiterFailure.getCause() instanceof CacheSingleFlightTimeoutException,
                    () -> "Unexpected waiter failure: " + waiterFailure.getCause());
            assertEquals(1, loaderCalls.get(), "Waiter timeout must not start a second DB loader");
            assertTrue(meterRegistry.get("cache.singleflight.waiter").counter().count() > 0);
            assertEquals(1.0, meterRegistry.get("cache.singleflight.timeout").counter().count());

            releaseLoader.countDown();
            assertEquals("slow-leader-value", leader.get(5, TimeUnit.SECONDS));

            // 清空 L1 后再次读取，验证 leader finally 已清理 future，下一轮可以重新加载。
            cacheService.clearLocalCache();
            assertEquals("recovered-value", cacheService.get(
                    cacheKey, String.class, () -> {
                        loaderCalls.incrementAndGet();
                        return "recovered-value";
                    }, 60L));
            assertEquals(2, loaderCalls.get());
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedLeaderShouldRemoveSingleFlightFutureForNextRequest() {
        String cacheKey = CACHE_KEY + ":loader-failure";
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService(
                meterRegistry, testProperties());
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getStrict(cacheKey))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        RedisCacheService cacheService = new RedisCacheService(
                redisUtil,
                new ObjectMapper(),
                meterRegistry,
                Caffeine.newBuilder().maximumSize(100).build(),
                circuitBreakerService,
                100L);
        AtomicInteger loaderCalls = new AtomicInteger();

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> cacheService.get(cacheKey, String.class, () -> {
                    loaderCalls.incrementAndGet();
                    throw new IllegalStateException("database unavailable");
                }, 60L));

        assertEquals("database unavailable", failure.getMessage());
        assertEquals("recovered-value", cacheService.get(cacheKey, String.class, () -> {
            loaderCalls.incrementAndGet();
            return "recovered-value";
        }, 60L));
        assertEquals(2, loaderCalls.get());
        assertEquals(1.0, meterRegistry.get("cache.singleflight.failure").counter().count());
    }

    private void assertRedisFailureSingleFlight(String cacheKey, boolean readFailure) throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerService circuitBreakerService = new CircuitBreakerService(
                meterRegistry, testProperties());
        RedisUtil redisUtil = mock(RedisUtil.class);
        if (readFailure) {
            when(redisUtil.getStrict(cacheKey))
                    .thenThrow(new IllegalStateException("Redis unavailable"));
        } else {
            when(redisUtil.getStrict(cacheKey)).thenReturn(null);
        }
        when(redisUtil.evalStrict(anyString(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("Redis script unavailable"));

        Cache<Object, Object> localCache = Caffeine.newBuilder()
                .maximumSize(100)
                .recordStats()
                .build();
        RedisCacheService cacheService = new RedisCacheService(
                redisUtil,
                new ObjectMapper(),
                meterRegistry,
                localCache,
                circuitBreakerService);

        int requestCount = 128;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loaderCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<String>> futures = new ArrayList<>(requestCount);

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cacheService.get(cacheKey, String.class, () -> {
                        loaderCalls.incrementAndGet();
                        loaderStarted.countDown();
                        try {
                            assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while releasing test loader", exception);
                        }
                        return "database-fallback-value";
                    }, 60L);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            releaseLoader.countDown();

            for (Future<String> future : futures) {
                assertEquals("database-fallback-value", future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, loaderCalls.get());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getRedisState());
        assertEquals(1.0, meterRegistry.get("cache.singleflight.leader").counter().count());
        assertTrue(meterRegistry.get("resilience.circuit_breaker.fallbacks")
                .tag("dependency", "redis")
                .counter()
                .count() > 0);
    }

    private static CircuitBreakerProperties testProperties() {
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setRedis(new CircuitBreakerProperties.Settings(
                50,
                100,
                Duration.ofSeconds(10).toMillis(),
                1_000,
                1,
                4,
                4,
                true));
        return properties;
    }
}
