package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqOutboxStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes committed outbox rows independently from the request thread.
 * Database row claims, rather than a process-local lock, provide multi-instance
 * safety and allow a stale lease to be recovered after a process crash.
 */
@Component
@ConditionalOnProperty(name = "mq.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class MqOutboxRelay implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MqOutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    private final MqOutboxMapper outboxMapper;
    private final RabbitMQSender rabbitMQSender;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxRetryCount;
    private final int retryBackoffSeconds;
    private final int maxBackoffSeconds;
    private final ExecutorService relayExecutor;
    private final Counter dispatchedCounter;
    private final Counter failedCounter;
    private final Counter deadLetteredCounter;
    private final Counter skippedCounter;

    @Autowired
    public MqOutboxRelay(MqOutboxMapper outboxMapper,
                         RabbitMQSender rabbitMQSender,
                         MeterRegistry meterRegistry,
                         @Value("${mq.outbox.batch-size:50}") int batchSize,
                         @Value("${mq.outbox.lease-seconds:60}") int leaseSeconds,
                         @Value("${mq.outbox.max-retry-count:10}") int maxRetryCount,
                         @Value("${mq.outbox.retry-backoff-seconds:5}") int retryBackoffSeconds,
                         @Value("${mq.outbox.max-backoff-seconds:300}") int maxBackoffSeconds,
                         @Value("${mq.outbox.worker-threads:4}") int workerThreads) {
        this.outboxMapper = outboxMapper;
        this.rabbitMQSender = rabbitMQSender;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.maxRetryCount = Math.max(1, maxRetryCount);
        this.retryBackoffSeconds = Math.max(1, retryBackoffSeconds);
        this.maxBackoffSeconds = Math.max(this.retryBackoffSeconds, maxBackoffSeconds);
        int boundedWorkerThreads = Math.max(1, Math.min(this.batchSize, workerThreads));
        this.relayExecutor = Executors.newFixedThreadPool(boundedWorkerThreads, runnable -> {
            Thread thread = new Thread(runnable,
                    "mq-outbox-relay-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.dispatchedCounter = counter(registry, "mq.outbox.dispatched", "Outbox rows confirmed by RabbitMQ");
        this.failedCounter = counter(registry, "mq.outbox.failed", "Outbox relay failures eligible for retry");
        this.deadLetteredCounter = counter(registry, "mq.outbox.dead_lettered", "Outbox rows moved to terminal dead-letter state");
        this.skippedCounter = counter(registry, "mq.outbox.skipped", "Outbox rows skipped because another worker claimed them");
    }

    /**
     * Lightweight constructor for unit tests.
     */
    public MqOutboxRelay(MqOutboxMapper outboxMapper, RabbitMQSender rabbitMQSender) {
        this(outboxMapper, rabbitMQSender, new SimpleMeterRegistry(), 50, 60, 10, 5, 300, 4);
    }

    MqOutboxRelay(MqOutboxMapper outboxMapper, RabbitMQSender rabbitMQSender, int workerThreads) {
        this(outboxMapper, rabbitMQSender, new SimpleMeterRegistry(), 50, 60, 10, 5, 300, workerThreads);
    }

    @Scheduled(fixedDelayString = "${mq.outbox.relay.fixed-delay-ms:1000}")
    public void relay() {
        List<MqOutbox> candidates;
        try {
            candidates = outboxMapper.selectDispatchable(batchSize, maxRetryCount);
        } catch (Exception exception) {
            logger.error("Failed to load dispatchable MQ outbox rows", exception);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        List<Callable<Void>> tasks = new ArrayList<>(candidates.size());
        for (MqOutbox candidate : candidates) {
            if (candidate != null && candidate.getId() != null) {
                tasks.add(() -> {
                    processCandidate(candidate);
                    return null;
                });
            }
        }
        if (tasks.isEmpty()) {
            return;
        }

        try {
            List<Future<Void>> futures = relayExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (CancellationException exception) {
                    logger.debug("MQ outbox worker task was cancelled during shutdown");
                } catch (ExecutionException exception) {
                    logger.error("Unexpected uncaught MQ outbox worker failure", exception.getCause());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("MQ outbox relay batch was interrupted");
        } catch (RejectedExecutionException exception) {
            logger.debug("MQ outbox relay batch rejected because the executor is shutting down");
        }
    }

    private void processCandidate(MqOutbox candidate) {
        Date leaseUntil = Date.from(Instant.now().plusSeconds(leaseSeconds));
        int claimed;
        try {
            claimed = outboxMapper.claim(candidate.getId(), leaseUntil, maxRetryCount);
        } catch (Exception exception) {
            logger.warn("Failed to claim MQ outbox row, outboxId={}", candidate.getId(), exception);
            return;
        }
        if (claimed == 0) {
            skippedCounter.increment();
            return;
        }

        try {
            boolean accepted = rabbitMQSender.publishOutboxMessage(candidate);
            if (accepted) {
                if (outboxMapper.markDispatched(candidate.getId()) == 1) {
                    dispatchedCounter.increment();
                }
            } else {
                markFailed(candidate, "RabbitMQ publish was rejected locally", false);
            }
        } catch (IllegalArgumentException exception) {
            markFailed(candidate, exception.getMessage(), true);
        } catch (Exception exception) {
            logger.warn("MQ outbox relay attempt failed, outboxId={}, eventId={}",
                    candidate.getId(), candidate.getEventId(), exception);
            markFailed(candidate, exception.getMessage(), false);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        relayExecutor.shutdown();
        try {
            if (!relayExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                List<Runnable> droppedTasks = relayExecutor.shutdownNow();
                for (Runnable droppedTask : droppedTasks) {
                    if (droppedTask instanceof Future<?> future) {
                        future.cancel(false);
                    }
                }
                logger.warn("Forced MQ outbox relay shutdown with {} queued tasks", droppedTasks.size());
            }
        } catch (InterruptedException exception) {
            relayExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void markFailed(MqOutbox candidate, String error, boolean terminal) {
        int currentRetryCount = candidate.getRetryCount() == null ? 0 : candidate.getRetryCount();
        int nextRetryCount = terminal ? currentRetryCount : currentRetryCount + 1;
        boolean deadLettered = terminal || nextRetryCount >= maxRetryCount;
        int nextStatus = deadLettered ? MqOutboxStatusConstant.DEAD_LETTERED : MqOutboxStatusConstant.FAILED;
        Date nextAttemptTime = deadLettered ? null : Date.from(
                Instant.now().plusSeconds(backoffSeconds(nextRetryCount)));
        String normalizedError = normalizeError(error);
        try {
            int updated = outboxMapper.markFailed(candidate.getId(), nextStatus, nextRetryCount,
                    nextAttemptTime, normalizedError);
            if (updated == 1) {
                if (deadLettered) {
                    deadLetteredCounter.increment();
                    logger.error("MQ outbox row moved to dead-letter state, outboxId={}, eventId={}, error={}",
                            candidate.getId(), candidate.getEventId(), normalizedError);
                } else {
                    failedCounter.increment();
                    logger.warn("MQ outbox row scheduled for retry, outboxId={}, eventId={}, retryCount={}, error={}",
                            candidate.getId(), candidate.getEventId(), nextRetryCount, normalizedError);
                }
            }
        } catch (Exception exception) {
            logger.error("Failed to persist MQ outbox failure state, outboxId={}, eventId={}",
                    candidate.getId(), candidate.getEventId(), exception);
        }
    }

    private long backoffSeconds(int retryCount) {
        int exponent = Math.max(0, Math.min(retryCount - 1, 10));
        long multiplier = 1L << exponent;
        return Math.min((long) retryBackoffSeconds * multiplier, maxBackoffSeconds);
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown MQ outbox failure";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}
