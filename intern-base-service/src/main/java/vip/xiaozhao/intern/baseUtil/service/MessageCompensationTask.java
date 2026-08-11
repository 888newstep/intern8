package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Periodic compensation task for failed MQ messages.
 * Uses a distributed lock to prevent multiple instances from executing simultaneously.
 */
@Component
public class MessageCompensationTask {

    private static final Logger logger = LoggerFactory.getLogger(MessageCompensationTask.class);
    private static final int BATCH_SIZE = 100;
    private static final String COMPENSATION_LOCK_KEY = DistributedLockService.LOCK_PREFIX + "compensation:mq_message";
    private static final long LOCK_WAIT_SECONDS = 1L;
    private static final long LOCK_LEASE_SECONDS = 55L;
    private static final long MAX_RUN_SECONDS = 45L;

    private final MqMessageStatusMapper messageStatusMapper;
    private final RabbitMQSender rabbitMQSender;
    private final DistributedLockService lockService;
    private final Counter compensationSuccessCounter;
    private final Counter compensationFailedCounter;
    private final Counter compensationSkippedCounter;
    private final Counter compensationTimeoutCounter;

    public MessageCompensationTask(MqMessageStatusMapper messageStatusMapper,
                                   RabbitMQSender rabbitMQSender,
                                   DistributedLockService lockService,
                                   MeterRegistry meterRegistry) {
        this.messageStatusMapper = messageStatusMapper;
        this.rabbitMQSender = rabbitMQSender;
        this.lockService = lockService;
        this.compensationSuccessCounter = Counter.builder("mq.compensation.success")
                .description("Successfully compensated messages")
                .register(meterRegistry);
        this.compensationFailedCounter = Counter.builder("mq.compensation.failed")
                .description("Failed compensation attempts")
                .register(meterRegistry);
        this.compensationSkippedCounter = Counter.builder("mq.compensation.skipped")
                .description("Skipped compensation (lock held by another instance)")
                .register(meterRegistry);
        this.compensationTimeoutCounter = Counter.builder("mq.compensation.timeout")
                .description("Compensation runs that reached the execution deadline")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 60000)
    public void compensateFailedMessages() {
        // Distributed lock: only one instance executes at a time
        boolean locked = lockService.executeWithLockVoid(
                COMPENSATION_LOCK_KEY, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, () -> {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_RUN_SECONDS);
            List<MqMessageStatus> failedMessages = messageStatusMapper.selectCompensableMessages(
                    RabbitMQConfig.MAX_RETRY_COUNT,
                    MqMessageStatusConstant.COMPENSATING_STALE_SECONDS,
                    BATCH_SIZE
            );

            if (failedMessages == null || failedMessages.isEmpty()) {
                return;
            }

            logger.info("Compensation task picked {} failed messages", failedMessages.size());
            for (MqMessageStatus messageStatus : failedMessages) {
                if (System.nanoTime() >= deadlineNanos) {
                    compensationTimeoutCounter.increment();
                    logger.warn("Compensation task reached execution deadline; remaining messages stay pending");
                    break;
                }
                try {
                    if (rabbitMQSender.republishFailedMessage(messageStatus)) {
                        compensationSuccessCounter.increment();
                    } else {
                        compensationFailedCounter.increment();
                    }
                } catch (Exception e) {
                    compensationFailedCounter.increment();
                    logger.error("Failed to compensate message, msgId: {}", messageStatus.getMessageId(), e);
                }
            }
        });

        if (!locked) {
            compensationSkippedCounter.increment();
            logger.debug("Compensation task skipped: another instance holds the lock");
        }
    }
}
