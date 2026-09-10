package vip.xiaozhao.intern.baseUtil.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分布式限流服务（基于Redisson）
 * 解决：限制用户每分钟发布动态次数，防止刷屏/恶意请求
 * 原理：Redisson RRateLimiter 基于 Redis 令牌桶算法实现，分布式下精确限流
 */
@Service
public class RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterService.class);

    // ======================= 限流规则 =======================
    /** 限流前缀 */
    private static final String RATE_LIMITER_PREFIX = "rate:limiter:";

    /** 用户发布动态：每分钟最多5条 */
    public static final String LIMITER_DYNAMIC_PUBLISH = RATE_LIMITER_PREFIX + "dynamic:publish:";
    public static final long PUBLISH_RATE = 5L;
    public static final long PUBLISH_INTERVAL = 1L;
    public static final RateIntervalUnit PUBLISH_INTERVAL_UNIT = RateIntervalUnit.MINUTES;

    /** 动态和评论的所有点赞/取消点赞操作：每分钟最多30次 */
    public static final String LIMITER_DYNAMIC_LIKE = RATE_LIMITER_PREFIX + "dynamic:like:";
    public static final long LIKE_RATE = 30L;
    public static final long LIKE_INTERVAL = 1L;
    public static final RateIntervalUnit LIKE_INTERVAL_UNIT = RateIntervalUnit.MINUTES;

    /** 用户评论：每分钟最多20次 */
    public static final String LIMITER_DYNAMIC_COMMENT = RATE_LIMITER_PREFIX + "dynamic:comment:";
    public static final long COMMENT_RATE = 20L;
    public static final long COMMENT_INTERVAL = 1L;
    public static final RateIntervalUnit COMMENT_INTERVAL_UNIT = RateIntervalUnit.MINUTES;

    private final RedissonClient redissonClient;

    public RateLimiterService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取一个许可（通用限流检查）
     *
     * @param limiterKey  限流器Key
     * @param rate        速率（许可数）
     * @param interval    时间间隔
     * @param intervalUnit 时间单位
     * @return true=允许通过，false=被限流
     */
    public boolean tryAcquire(String limiterKey, long rate, long interval, RateIntervalUnit intervalUnit) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);
        // 初始化限流器（只初始化一次，Redis存储状态）
        rateLimiter.trySetRate(RateType.OVERALL, rate, interval, intervalUnit);

        boolean acquired = rateLimiter.tryAcquire(1);
        if (!acquired) {
            logger.warn("Rate limited: {} (rate={}/{})", limiterKey, rate, intervalUnit);
        }
        return acquired;
    }

    /**
     * 检查用户是否可发布动态（每分钟最多5条）
     */
    public boolean tryAcquirePublish(Long userId) {
        return tryAcquire(LIMITER_DYNAMIC_PUBLISH + userId,
                PUBLISH_RATE, PUBLISH_INTERVAL, PUBLISH_INTERVAL_UNIT);
    }

    /**
     * 检查用户是否可执行动态或评论点赞操作（每分钟最多30次）
     */
    public boolean tryAcquireLike(Long userId) {
        return tryAcquire(LIMITER_DYNAMIC_LIKE + userId,
                LIKE_RATE, LIKE_INTERVAL, LIKE_INTERVAL_UNIT);
    }

    /**
     * 检查用户是否可评论（每分钟最多20次）
     */
    public boolean tryAcquireComment(Long userId) {
        return tryAcquire(LIMITER_DYNAMIC_COMMENT + userId,
                COMMENT_RATE, COMMENT_INTERVAL, COMMENT_INTERVAL_UNIT);
    }

    /**
     * 获取当前限流器的剩余许可数
     */
    public long availablePermits(String limiterKey) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);
        return rateLimiter.availablePermits();
    }
}
