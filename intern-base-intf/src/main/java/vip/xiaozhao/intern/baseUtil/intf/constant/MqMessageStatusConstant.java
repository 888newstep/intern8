package vip.xiaozhao.intern.baseUtil.intf.constant;

/**
 * MQ message lifecycle states persisted in {@code mq_message_status}.
 *
 * <p>The state is intentionally stored as a small integer so it remains
 * compatible with the existing schema. Transitions are enforced by
 * conditional SQL updates instead of relying on callback ordering.</p>
 */
public final class MqMessageStatusConstant {

    /** Message status row has been created, but broker confirmation is pending. */
    public static final int PENDING = 0;
    /** RabbitMQ confirmed the publish at the broker/exchange boundary. */
    public static final int CONFIRMED = 1;
    /** Publish or routing failed and the message can be compensated. */
    public static final int FAILED = 2;
    /** Consumer completed the business side effect successfully. */
    public static final int CONSUMED = 3;
    /** Consumer failed; the message can be retried or compensated. */
    public static final int CONSUME_FAILED = 4;
    /** A compensation worker has claimed the row and is republishing it. */
    public static final int COMPENSATING = 5;
    /** Payload or route is invalid and requires manual DLQ handling. */
    public static final int DEAD_LETTERED = 6;
    /** A pending broker confirm or compensation claim may be recovered after this period. */
    public static final int COMPENSATING_STALE_SECONDS = 300;

    private MqMessageStatusConstant() {
    }
}
