package vip.xiaozhao.intern.baseUtil.intf.constant;

/**
 * Transactional outbox lifecycle states.
 */
public final class MqOutboxStatusConstant {

    /** Waiting for a relay worker to publish the event. */
    public static final int PENDING = 0;
    /** Claimed by one relay worker and protected by a lease. */
    public static final int PROCESSING = 1;
    /** Accepted by RabbitTemplate; broker confirm is tracked separately. */
    public static final int DISPATCHED = 2;
    /** Publish failed and can be retried after nextAttemptTime. */
    public static final int FAILED = 3;
    /** Retry budget exhausted or payload is invalid. */
    public static final int DEAD_LETTERED = 4;

    private MqOutboxStatusConstant() {
    }
}
