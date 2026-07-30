package vip.xiaozhao.intern.baseUtil.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 雪花算法全局唯一ID生成器
 * 结构：1位符号位 + 41位时间戳(ms) + 10位WorkerID + 12位序列号
 * 支持：单机QPS约409.6万，集群最多1024个节点，可用至2089年
 * 适配：未来分库分表场景，ID全局唯一且趋势递增
 */
public class SnowflakeIdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    // ======================= 固定常量 =======================
    /** 开始时间戳（2024-01-01），避免ID过长 */
    private static final long EPOCH = 1704067200000L;

    /** 机器ID所占位数 */
    private static final long WORKER_ID_BITS = 10L;

    /** 序列号所占位数 */
    private static final long SEQUENCE_BITS = 12L;

    /** 机器ID最大值 1023 */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    /** 序列号最大值 4095 */
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    /** 机器ID左移位数 12 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /** 时间戳左移位数 22（10+12） */
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    // ======================= 运行时变量 =======================
    /** 机器ID（0~1023） */
    private final long workerId;

    /** 上次生成ID的时间戳 */
    private long lastTimestamp = -1L;

    /** 当前毫秒内的序列号 */
    private long sequence = 0L;

    /**
     * @param workerId 机器ID（0~1023），可通过配置文件或Redis自增分配
     */
    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(String.format(
                    "Worker ID must be 0 ~ %d, got: %d", MAX_WORKER_ID, workerId));
        }
        this.workerId = workerId;
        logger.info("SnowflakeIdGenerator initialized with workerId: {}", workerId);
    }

    /**
     * 生成下一个全局唯一ID
     * 线程安全：synchronized 保证毫秒内序列号递增
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        // 时钟回拨：容忍5ms内的回拨，等待时钟追上
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                // 等待时钟追上
                try {
                    wait(lastTimestamp - timestamp);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                timestamp = currentTimeMillis();
            } else {
                // 时钟回拨超过5ms，抛出异常
                logger.error("Clock moved backwards more than 5ms, refusing to generate id");
                throw new RuntimeException(
                        String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", offset));
            }
        }

        // 同一毫秒内，序列号递增
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 当前毫秒序列号耗尽，等待下一毫秒
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒，序列号重置为0
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装ID：时间戳差值左移22位 | 机器ID左移12位 | 序列号
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 从ID中解析出时间戳（毫秒）
     */
    public long extractTimestamp(long id) {
        return (id >>> TIMESTAMP_SHIFT) + EPOCH;
    }

    /**
     * 从ID中解析出机器ID
     */
    public long extractWorkerId(long id) {
        return (id >>> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    /**
     * 从ID中解析出序列号
     */
    public long extractSequence(long id) {
        return id & MAX_SEQUENCE;
    }

    private long waitUntilNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}