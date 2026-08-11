package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqOutboxServiceTest {

    @Mock
    private MqOutboxMapper outboxMapper;

    @Test
    void enqueue_ShouldPersistEventAndRoute() {
        MqOutboxService service = new MqOutboxService(outboxMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "liked", "300");

        assertTrue(service.enqueue(event, "notification.exchange", "notification.created"));

        ArgumentCaptor<MqOutbox> captor = ArgumentCaptor.forClass(MqOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        MqOutbox saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(event.getEventId(), saved.getEventId());
        org.junit.jupiter.api.Assertions.assertEquals(event.getEventType(), saved.getEventType());
        org.junit.jupiter.api.Assertions.assertEquals("notification.exchange", saved.getExchangeName());
        org.junit.jupiter.api.Assertions.assertEquals("notification.created", saved.getRoutingKey());
        org.junit.jupiter.api.Assertions.assertEquals(0, saved.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(0, saved.getRetryCount());
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getMessageBody());
    }

    @Test
    void enqueue_ShouldTreatIdenticalDuplicateAsIdempotent() {
        MqOutboxService service = new MqOutboxService(outboxMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "liked", "300");
        MqOutbox existing = new MqOutbox();
        existing.setEventId(event.getEventId());
        existing.setEventType(event.getEventType());
        existing.setExchangeName("notification.exchange");
        existing.setRoutingKey("notification.created");
        try {
            existing.setMessageBody(new ObjectMapper().writeValueAsString(event));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        doThrow(new DuplicateKeyException("duplicate"))
                .when(outboxMapper).insert(any(MqOutbox.class));
        when(outboxMapper.selectByEventId(event.getEventId())).thenReturn(existing);

        assertTrue(service.enqueue(event, "notification.exchange", "notification.created"));
    }

    @Test
    void enqueue_ShouldRejectDuplicateEventIdWithDifferentPayload() throws Exception {
        MqOutboxService service = new MqOutboxService(outboxMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "liked", "300");
        MqOutbox existing = new MqOutbox();
        existing.setEventId(event.getEventId());
        existing.setEventType(event.getEventType());
        existing.setExchangeName("notification.exchange");
        existing.setRoutingKey("notification.created");
        existing.setMessageBody("different-payload");

        doThrow(new DuplicateKeyException("duplicate"))
                .when(outboxMapper).insert(any(MqOutbox.class));
        when(outboxMapper.selectByEventId(event.getEventId())).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> service.enqueue(event, "notification.exchange", "notification.created"));
    }

    @Test
    void enqueue_WhenDisabled_ShouldNotWriteDatabase() {
        MqOutboxService service = new MqOutboxService(
                outboxMapper, new ObjectMapper(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), false);
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "liked", "300");

        org.junit.jupiter.api.Assertions.assertFalse(
                service.enqueue(event, "notification.exchange", "notification.created"));
        verify(outboxMapper, never()).insert(any(MqOutbox.class));
    }
}
