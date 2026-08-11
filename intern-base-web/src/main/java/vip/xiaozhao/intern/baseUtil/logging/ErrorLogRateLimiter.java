package vip.xiaozhao.intern.baseUtil.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small bounded per-key gate for noisy error logs. It is deliberately local:
 * metrics and alerting remain responsible for cross-instance aggregation.
 */
@Component
public class ErrorLogRateLimiter {

    private static final int MAX_KEYS = 1024;
    private final long intervalNanos;
    private final Map<String, AtomicLong> nextAllowedNanos = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    public ErrorLogRateLimiter(
            @Value("${logging.error-sample-interval-ms:10000}") long intervalMillis) {
        long boundedMillis = Math.max(100L, Math.min(intervalMillis, 600_000L));
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(boundedMillis);
    }

    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = System.nanoTime();
        AtomicLong nextAllowed = nextAllowedNanos.get(key);
        if (nextAllowed == null) {
            synchronized (admissionLock) {
                nextAllowed = nextAllowedNanos.get(key);
                if (nextAllowed == null) {
                    cleanup(now);
                    if (nextAllowedNanos.size() >= MAX_KEYS) {
                        return false;
                    }
                    nextAllowed = new AtomicLong(0L);
                    nextAllowedNanos.put(key, nextAllowed);
                }
            }
        }
        while (true) {
            long previous = nextAllowed.get();
            if (now < previous) {
                return false;
            }
            if (nextAllowed.compareAndSet(previous, now + intervalNanos)) {
                cleanup(now);
                return true;
            }
        }
    }

    private void cleanup(long now) {
        nextAllowedNanos.entrySet().removeIf(entry -> entry.getValue().get() < now);
    }
}
