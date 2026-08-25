package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQ 补偿调度边界配置。
 *
 * <p>单条超时只限制补偿调度线程的等待时间，不承诺强制终止第三方客户端
 * 已经进入的 socket I/O；RabbitMQ 客户端连接超时仍是底层最后一道边界。</p>
 */
@Component
@ConfigurationProperties(prefix = "mq.compensation")
public class CompensationProperties {

    private static final int MAX_WORKER_THREADS = 16;

    private long maxRunSeconds = 45L;
    private long perMessageTimeoutMs = 5_000L;
    private int workerThreads = 4;

    public long getMaxRunSeconds() {
        return maxRunSeconds;
    }

    public void setMaxRunSeconds(long maxRunSeconds) {
        if (maxRunSeconds <= 0) {
            throw new IllegalArgumentException("mq.compensation.max-run-seconds must be positive");
        }
        this.maxRunSeconds = maxRunSeconds;
    }

    public long getPerMessageTimeoutMs() {
        return perMessageTimeoutMs;
    }

    public void setPerMessageTimeoutMs(long perMessageTimeoutMs) {
        if (perMessageTimeoutMs <= 0) {
            throw new IllegalArgumentException("mq.compensation.per-message-timeout-ms must be positive");
        }
        this.perMessageTimeoutMs = perMessageTimeoutMs;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        if (workerThreads <= 0 || workerThreads > MAX_WORKER_THREADS) {
            throw new IllegalArgumentException(
                    "mq.compensation.worker-threads must be between 1 and " + MAX_WORKER_THREADS);
        }
        this.workerThreads = workerThreads;
    }
}
