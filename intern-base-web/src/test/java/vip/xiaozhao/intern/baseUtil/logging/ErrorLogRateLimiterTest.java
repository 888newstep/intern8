package vip.xiaozhao.intern.baseUtil.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogRateLimiterTest {

    @Test
    void allowsOneErrorPerKeyAndInterval() {
        ErrorLogRateLimiter limiter = new ErrorLogRateLimiter(600_000L);

        assertTrue(limiter.tryAcquire("handler:IllegalStateException"));
        assertFalse(limiter.tryAcquire("handler:IllegalStateException"));
        assertTrue(limiter.tryAcquire("other-handler:IllegalStateException"));
    }

    @Test
    void rejectsNewKeysAfterCapacityIsReached() {
        ErrorLogRateLimiter limiter = new ErrorLogRateLimiter(600_000L);

        for (int i = 0; i < 1024; i++) {
            assertTrue(limiter.tryAcquire("handler-" + i));
        }

        assertFalse(limiter.tryAcquire("handler-overflow"));
        assertFalse(limiter.tryAcquire("handler-0"));
    }
}
