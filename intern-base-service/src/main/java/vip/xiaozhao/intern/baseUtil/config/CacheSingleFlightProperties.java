package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地 single-flight 等待策略配置。
 *
 * <p>等待超时只限制 waiter 占用请求线程的时间，不会取消正在执行的 leader。
 * 这样既能保护线程池，也不会破坏共享加载任务的生命周期。</p>
 */
@Component
@ConfigurationProperties(prefix = "cache.single-flight")
public class CacheSingleFlightProperties {

    public static final long DEFAULT_WAIT_TIMEOUT_MS = 3_000L;

    private long waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS;

    public long getWaitTimeoutMs() {
        return waitTimeoutMs;
    }

    public void setWaitTimeoutMs(long waitTimeoutMs) {
        if (waitTimeoutMs <= 0) {
            throw new IllegalArgumentException("cache.single-flight.wait-timeout-ms must be positive");
        }
        this.waitTimeoutMs = waitTimeoutMs;
    }
}
