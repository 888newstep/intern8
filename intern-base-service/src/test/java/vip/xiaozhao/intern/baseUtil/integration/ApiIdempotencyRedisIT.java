package vip.xiaozhao.intern.baseUtil.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.service.ApiIdempotencyService;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiIdempotencyRedisIT extends AbstractRedisContainerIT {

    @Test
    void scriptsShouldPersistDoneResponseAndRejectFingerprintConflict() {
        try (RedissonRedisCommandClientAdapter redis =
                     RedissonRedisCommandClientAdapter.connect(redisAddress())) {
            ApiIdempotencyService service = newService(redis);
            AtomicInteger sideEffectCount = new AtomicInteger();

            ResponseDO first = service.execute(100L, "/it/write", "same-key",
                    Map.of("content", "hello"), () -> {
                        sideEffectCount.incrementAndGet();
                        return ResponseDO.success("created");
                    });
            ResponseDO duplicate = service.execute(100L, "/it/write", "same-key",
                    Map.of("content", "hello"), () -> {
                        sideEffectCount.incrementAndGet();
                        return ResponseDO.success("must-not-run");
                    });
            ResponseDO conflict = service.execute(100L, "/it/write", "same-key",
                    Map.of("content", "different"), () -> ResponseDO.success("must-not-run"));

            assertTrue(first.isSuccess());
            assertTrue(duplicate.isSuccess());
            assertEquals("created", duplicate.getData());
            assertEquals(1, sideEffectCount.get());
            assertFalse(conflict.isSuccess());
            assertEquals(409, conflict.getErrorCode());
        }
    }

    @Test
    void concurrentSameKeyRequestsShouldExecuteOnlyOneBusinessOperation() throws Exception {
        try (RedissonRedisCommandClientAdapter redis =
                     RedissonRedisCommandClientAdapter.connect(redisAddress())) {
            ApiIdempotencyService service = newService(redis);
            AtomicInteger sideEffectCount = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(16);
            try {
                Future<?>[] futures = new Future<?>[32];
                for (int i = 0; i < futures.length; i++) {
                    futures[i] = executor.submit(() -> {
                        await(start);
                        service.execute(200L, "/it/concurrent", "same-key",
                                Map.of("value", "x"), () -> {
                                    sideEffectCount.incrementAndGet();
                                    sleep(150);
                                    return ResponseDO.success("ok");
                                });
                    });
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
                assertEquals(1, sideEffectCount.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private ApiIdempotencyService newService(RedissonRedisCommandClientAdapter redis) {
        return new ApiIdempotencyService(redis, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting concurrent test", exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during idempotent operation", exception);
        }
    }
}
