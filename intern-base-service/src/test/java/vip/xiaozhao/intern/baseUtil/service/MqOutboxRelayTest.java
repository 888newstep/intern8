package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqOutboxStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqOutboxRelayTest {

    @Mock
    private MqOutboxMapper outboxMapper;

    @Mock
    private RabbitMQSender rabbitMQSender;

    @Test
    void relay_ShouldClaimPublishAndMarkDispatched() {
        MqOutbox candidate = candidate();
        when(outboxMapper.selectDispatchable(50, 10)).thenReturn(List.of(candidate));
        when(outboxMapper.claim(eq(1L), any(Date.class), eq(10))).thenReturn(1);
        when(rabbitMQSender.publishOutboxMessage(candidate)).thenReturn(true);
        when(outboxMapper.markDispatched(1L)).thenReturn(1);

        try (MqOutboxRelay relay = new MqOutboxRelay(outboxMapper, rabbitMQSender)) {
            relay.relay();
        }

        verify(rabbitMQSender).publishOutboxMessage(candidate);
        verify(outboxMapper).markDispatched(1L);
        verify(outboxMapper, never()).markFailed(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void relay_WhenLocalPublishFails_ShouldScheduleRetry() {
        MqOutbox candidate = candidate();
        when(outboxMapper.selectDispatchable(50, 10)).thenReturn(List.of(candidate));
        when(outboxMapper.claim(eq(1L), any(Date.class), eq(10))).thenReturn(1);
        when(rabbitMQSender.publishOutboxMessage(candidate)).thenReturn(false);
        when(outboxMapper.markFailed(eq(1L), eq(MqOutboxStatusConstant.FAILED), eq(1),
                any(Date.class), any(String.class))).thenReturn(1);

        try (MqOutboxRelay relay = new MqOutboxRelay(outboxMapper, rabbitMQSender)) {
            relay.relay();
        }

        verify(outboxMapper).markFailed(eq(1L), eq(MqOutboxStatusConstant.FAILED), eq(1),
                any(Date.class), any(String.class));
    }

    @Test
    void relay_WhenPayloadIsInvalid_ShouldMoveToDeadLetterState() {
        MqOutbox candidate = candidate();
        when(outboxMapper.selectDispatchable(50, 10)).thenReturn(List.of(candidate));
        when(outboxMapper.claim(eq(1L), any(Date.class), eq(10))).thenReturn(1);
        when(rabbitMQSender.publishOutboxMessage(candidate))
                .thenThrow(new IllegalArgumentException("invalid payload"));
        when(outboxMapper.markFailed(eq(1L), eq(MqOutboxStatusConstant.DEAD_LETTERED), eq(0),
                eq(null), any(String.class))).thenReturn(1);

        try (MqOutboxRelay relay = new MqOutboxRelay(outboxMapper, rabbitMQSender)) {
            relay.relay();
        }

        verify(outboxMapper).markFailed(eq(1L), eq(MqOutboxStatusConstant.DEAD_LETTERED), eq(0),
                eq(null), any(String.class));
    }

    @Test
    void relay_WhenClaimIsLost_ShouldNotPublish() {
        MqOutbox candidate = candidate();
        when(outboxMapper.selectDispatchable(50, 10)).thenReturn(List.of(candidate));
        when(outboxMapper.claim(eq(1L), any(Date.class), eq(10))).thenReturn(0);

        try (MqOutboxRelay relay = new MqOutboxRelay(outboxMapper, rabbitMQSender)) {
            relay.relay();
        }

        verify(rabbitMQSender, never()).publishOutboxMessage(any(MqOutbox.class));
    }

    @Test
    void relay_ShouldPublishBatchConcurrentlyWithinWorkerLimit() throws Exception {
        MqOutbox first = candidate();
        MqOutbox second = candidate();
        second.setId(2L);
        second.setEventId("event-2");
        CountDownLatch concurrentPublishers = new CountDownLatch(2);
        when(outboxMapper.selectDispatchable(50, 10)).thenReturn(List.of(first, second));
        when(outboxMapper.claim(anyLong(), any(Date.class), eq(10))).thenReturn(1);
        when(rabbitMQSender.publishOutboxMessage(any(MqOutbox.class))).thenAnswer(invocation -> {
            concurrentPublishers.countDown();
            assertTrue(concurrentPublishers.await(5, TimeUnit.SECONDS));
            return true;
        });
        when(outboxMapper.markDispatched(anyLong())).thenReturn(1);

        try (MqOutboxRelay relay = new MqOutboxRelay(outboxMapper, rabbitMQSender, 2)) {
            relay.relay();
        }

        verify(rabbitMQSender, times(2)).publishOutboxMessage(any(MqOutbox.class));
        verify(outboxMapper, times(2)).markDispatched(anyLong());
    }

    private MqOutbox candidate() {
        MqOutbox candidate = new MqOutbox();
        candidate.setId(1L);
        candidate.setEventId("event-1");
        candidate.setEventType("notification.created");
        candidate.setRetryCount(0);
        return candidate;
    }
}
