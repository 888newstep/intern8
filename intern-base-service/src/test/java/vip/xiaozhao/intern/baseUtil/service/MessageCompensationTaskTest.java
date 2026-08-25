package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.config.CompensationProperties;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCompensationTaskTest {

    @Mock
    private MqMessageStatusMapper messageStatusMapper;

    @Mock
    private RabbitMQSender rabbitMQSender;

    @Mock
    private DistributedLockService lockService;

    @Test
    void compensateFailedMessages_ShouldRunHealthyAttemptsConcurrently() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MqMessageStatus first = message("message-1");
        MqMessageStatus second = message("message-2");
        when(messageStatusMapper.selectCompensableMessages(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(first, second));
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothStarted.countDown();
            release.await();
            return true;
        }).when(rabbitMQSender).republishFailedMessage(any(MqMessageStatus.class));
        executeUnderLock();

        MessageCompensationTask task = newTask(properties(2_000, 45, 2), meterRegistry);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = scheduler.submit(task::compensateFailedMessages);
            assertTrue(bothStarted.await(1, TimeUnit.SECONDS),
                    "Both compensation workers should start before either is released");
            release.countDown();
            run.get(1, TimeUnit.SECONDS);

            verify(rabbitMQSender, times(2)).republishFailedMessage(any(MqMessageStatus.class));
            assertEquals(2.0, meterRegistry.get("mq.compensation.success").counter().count());
            assertEquals(0.0, meterRegistry.get("mq.compensation.message.timeout").counter().count());
        } finally {
            release.countDown();
            scheduler.shutdownNow();
            task.shutdown();
        }
    }

    @Test
    void compensateFailedMessages_WhenPublishBlocks_ShouldCancelAndStopBatch() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MqMessageStatus first = message("message-1");
        MqMessageStatus second = message("message-2");
        when(messageStatusMapper.selectCompensableMessages(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(first, second));

        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(invocation -> {
            MqMessageStatus messageStatus = invocation.getArgument(0);
            bothStarted.countDown();
            if (!"message-1".equals(messageStatus.getMessageId())) {
                return true;
            }
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return false;
        }).when(rabbitMQSender).republishFailedMessage(any(MqMessageStatus.class));
        executeUnderLock();

        MessageCompensationTask task = newTask(properties(500, 1, 2), meterRegistry);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            long startNanos = System.nanoTime();
            Future<?> run = scheduler.submit(task::compensateFailedMessages);
            assertTrue(bothStarted.await(1, TimeUnit.SECONDS),
                    "Both compensation attempts must start before the timeout assertion");
            run.get(2, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(interrupted.await(1, TimeUnit.SECONDS),
                    "Timed-out compensation worker must receive interruption");
            assertTrue(elapsedMillis < 1_500,
                    "Scheduler must return within the configured per-message timeout budget");
            verify(rabbitMQSender, times(2)).republishFailedMessage(any(MqMessageStatus.class));
            assertEquals(1.0, meterRegistry.get("mq.compensation.success").counter().count());
            assertEquals(1.0, meterRegistry.get("mq.compensation.message.timeout").counter().count());
            assertEquals(1.0, meterRegistry.get("mq.compensation.timeout").counter().count());
            assertEquals(0.0, meterRegistry.get("mq.compensation.failed").counter().count());
        } finally {
            scheduler.shutdownNow();
            task.shutdown();
        }
    }

    @Test
    void compensateFailedMessages_WhenTimedOutWorkerIgnoresInterrupt_ShouldSkipNextRun() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(messageStatusMapper.selectCompensableMessages(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(message("message-1")));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return false;
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                }
            }
        }).when(rabbitMQSender).republishFailedMessage(any(MqMessageStatus.class));
        executeUnderLock();

        MessageCompensationTask task = newTask(properties(500, 1, 1), meterRegistry);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = scheduler.submit(task::compensateFailedMessages);
            assertTrue(started.await(1, TimeUnit.SECONDS),
                    "Compensation worker must start before testing ignored interruption");
            run.get(2, TimeUnit.SECONDS);
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));

            task.compensateFailedMessages();

            verify(messageStatusMapper, times(1)).selectCompensableMessages(anyInt(), anyInt(), anyInt());
            verify(rabbitMQSender, times(1)).republishFailedMessage(any(MqMessageStatus.class));
            assertEquals(1.0, meterRegistry.get("mq.compensation.worker.busy").counter().count());
            assertEquals(2.0, meterRegistry.get("mq.compensation.timeout").counter().count());
        } finally {
            release.countDown();
            scheduler.shutdownNow();
            task.shutdown();
        }
    }

    @Test
    void constructor_ShouldRejectRunDeadlineThatCanOutliveLockLease() {
        CompensationProperties properties = properties(100, 55, 1);

        assertThrows(IllegalArgumentException.class,
                () -> newTask(properties, new SimpleMeterRegistry()));
    }

    @Test
    void compensationProperties_ShouldRejectUnboundedWorkerCounts() {
        CompensationProperties properties = new CompensationProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setWorkerThreads(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setWorkerThreads(17));
    }

    private MessageCompensationTask newTask(CompensationProperties properties,
                                            SimpleMeterRegistry meterRegistry) {
        return new MessageCompensationTask(
                messageStatusMapper,
                rabbitMQSender,
                lockService,
                meterRegistry,
                properties);
    }

    private void executeUnderLock() {
        doAnswer(invocation -> {
            Runnable callback = invocation.getArgument(3);
            callback.run();
            return true;
        }).when(lockService).executeWithLockVoid(
                anyString(), anyLong(), anyLong(), any(Runnable.class));
    }

    private CompensationProperties properties(long timeoutMs, long maxRunSeconds, int workers) {
        CompensationProperties properties = new CompensationProperties();
        properties.setPerMessageTimeoutMs(timeoutMs);
        properties.setMaxRunSeconds(maxRunSeconds);
        properties.setWorkerThreads(workers);
        return properties;
    }

    private MqMessageStatus message(String messageId) {
        MqMessageStatus status = new MqMessageStatus();
        status.setMessageId(messageId);
        return status;
    }
}
