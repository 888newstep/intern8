package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.config.CompensationProperties;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final MqMessageStatusMapper messageStatusMapper;
    private final RabbitMQSender rabbitMQSender;
    private final DistributedLockService lockService;
    private final CompensationProperties compensationProperties;
    private final ThreadPoolExecutor compensationExecutor;
    private final Counter compensationSuccessCounter;
    private final Counter compensationFailedCounter;
    private final Counter compensationSkippedCounter;
    private final Counter compensationTimeoutCounter;
    private final Counter compensationMessageTimeoutCounter;
    private final Counter compensationWorkerBusyCounter;

    @Autowired
    public MessageCompensationTask(MqMessageStatusMapper messageStatusMapper,
                                   RabbitMQSender rabbitMQSender,
                                   DistributedLockService lockService,
                                   MeterRegistry meterRegistry,
                                   CompensationProperties compensationProperties) {
        this.messageStatusMapper = messageStatusMapper;
        this.rabbitMQSender = rabbitMQSender;
        this.lockService = lockService;
        this.compensationProperties = compensationProperties;
        validateLockBudget(compensationProperties);
        this.compensationExecutor = new ThreadPoolExecutor(
                compensationProperties.getWorkerThreads(),
                compensationProperties.getWorkerThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(compensationProperties.getWorkerThreads()),
                compensationThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.compensationExecutor.prestartAllCoreThreads();
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
                .description("Compensation runs stopped by a deadline or occupied worker")
                .register(meterRegistry);
        this.compensationMessageTimeoutCounter = Counter.builder("mq.compensation.message.timeout")
                .description("Compensation attempts that exceeded the per-message timeout")
                .register(meterRegistry);
        this.compensationWorkerBusyCounter = Counter.builder("mq.compensation.worker.busy")
                .description("Compensation runs skipped while a prior timed-out worker is still active")
                .register(meterRegistry);
    }

    /** 保留纯单元测试和非 Spring 调用的兼容构造函数。 */
    public MessageCompensationTask(MqMessageStatusMapper messageStatusMapper,
                                   RabbitMQSender rabbitMQSender,
                                   DistributedLockService lockService,
                                   MeterRegistry meterRegistry) {
        this(messageStatusMapper, rabbitMQSender, lockService, meterRegistry, new CompensationProperties());
    }

    @Scheduled(fixedDelay = 60000)
    public void compensateFailedMessages() {
        // Distributed lock: only one instance executes at a time
        boolean locked = lockService.executeWithLockVoid(
                COMPENSATION_LOCK_KEY, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, () -> {
            if (hasOutstandingWorker()) {
                compensationWorkerBusyCounter.increment();
                recordRunTimeout("compensation worker is still occupied after a prior timeout");
                logger.warn("Compensation task skipped because prior external I/O is still stopping");
                return;
            }

            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(compensationProperties.getMaxRunSeconds());
            List<MqMessageStatus> failedMessages = messageStatusMapper.selectCompensableMessages(
                    RabbitMQConfig.MAX_RETRY_COUNT,
                    MqMessageStatusConstant.COMPENSATING_STALE_SECONDS,
                    BATCH_SIZE
            );

            if (failedMessages == null || failedMessages.isEmpty()) {
                return;
            }

            logger.info("Compensation task picked {} failed messages", failedMessages.size());
            int workerThreads = compensationProperties.getWorkerThreads();
            for (int start = 0; start < failedMessages.size(); start += workerThreads) {
                if (System.nanoTime() >= deadlineNanos) {
                    recordRunTimeout("overall deadline reached before next compensation wave");
                    logger.warn("Compensation task reached execution deadline; remaining messages stay pending");
                    break;
                }

                int end = Math.min(start + workerThreads, failedMessages.size());
                if (!compensateWave(failedMessages.subList(start, end), deadlineNanos)) {
                    break;
                }
            }
        });

        if (!locked) {
            compensationSkippedCounter.increment();
            logger.debug("Compensation task skipped: another instance holds the lock");
        }
    }

    private boolean compensateWave(List<MqMessageStatus> messages, long deadlineNanos) {
        long nowNanos = System.nanoTime();
        long remainingNanos = deadlineNanos - nowNanos;
        if (remainingNanos <= 0) {
            recordRunTimeout("overall deadline reached before submitting compensation wave");
            return false;
        }

        long waveTimeoutNanos = Math.min(
                TimeUnit.MILLISECONDS.toNanos(compensationProperties.getPerMessageTimeoutMs()),
                remainingNanos);
        List<Callable<Boolean>> tasks = messages.stream()
                .<Callable<Boolean>>map(message -> () -> rabbitMQSender.republishFailedMessage(message))
                .toList();

        List<Future<Boolean>> attempts;
        try {
            attempts = compensationExecutor.invokeAll(tasks, waveTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            recordRunTimeout("compensation wave was rejected by the bounded executor");
            logger.warn("Compensation wave rejected; external I/O may still be stopping");
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Compensation task interrupted; unfinished claims remain for stale recovery");
            return false;
        }

        int timedOut = 0;
        for (int index = 0; index < attempts.size(); index++) {
            Future<Boolean> attempt = attempts.get(index);
            if (attempt.isCancelled()) {
                timedOut++;
            } else {
                recordAttemptResult(attempt, messages.get(index).getMessageId());
            }
        }
        if (timedOut == 0) {
            return true;
        }

        compensationExecutor.purge();
        compensationMessageTimeoutCounter.increment(timedOut);
        recordRunTimeout("per-message timeout reached");
        logger.warn("Compensation wave timed out, waveSize={}, unfinished={}; leave claims for stale recovery",
                messages.size(), timedOut);
        return false;
    }

    private void recordAttemptResult(Future<Boolean> attempt, String messageId) {
        try {
            if (Boolean.TRUE.equals(attempt.get())) {
                compensationSuccessCounter.increment();
            } else {
                compensationFailedCounter.increment();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while collecting a completed compensation attempt, msgId={}", messageId);
        } catch (ExecutionException exception) {
            compensationFailedCounter.increment();
            logger.error("Failed to compensate message, msgId={}", messageId, exception.getCause());
        }
    }

    private boolean hasOutstandingWorker() {
        return compensationExecutor.getActiveCount() > 0 || !compensationExecutor.getQueue().isEmpty();
    }

    private void recordRunTimeout(String reason) {
        compensationTimeoutCounter.increment();
        logger.debug("MQ compensation run stopped: {}", reason);
    }

    private static void validateLockBudget(CompensationProperties properties) {
        if (properties.getMaxRunSeconds() >= LOCK_LEASE_SECONDS) {
            throw new IllegalArgumentException(
                    "mq.compensation.max-run-seconds must be less than the compensation lock lease");
        }
    }

    @PreDestroy
    void shutdown() {
        compensationExecutor.shutdownNow();
        try {
            if (!compensationExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("MQ compensation executor did not terminate within {} seconds",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException exception) {
            compensationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory compensationThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "mq-compensation-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
