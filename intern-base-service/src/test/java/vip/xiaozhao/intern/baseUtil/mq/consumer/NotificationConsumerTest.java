package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiNotificationMapper;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Mock
    private TuiNotificationMapper notificationMapper;

    @Mock
    private RedisUtil redisUtil;

    @Mock
    private RabbitMQSender rabbitMQSender;

    @Mock
    private MqMessageStatusMapper messageStatusMapper;

    @Mock
    private Channel channel;

    @Test
    void handleNotificationMessage_ShouldBeIdempotent() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
            notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        String msgId = "test-msg-123";
        String jsonBody = "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}";
        Message message = createTestMessage(msgId, jsonBody);
        
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doNothing().when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper, times(1)).insert(any());
        verify(redisUtil).incr("notification:unread:count:100");
    }

    @Test
    void handleNotificationMessage_ShouldUpdateStatusOnSuccess() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
            notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        String msgId = "test-msg-456";
        String jsonBody = "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}";
        Message message = createTestMessage(msgId, jsonBody);
        
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doNothing().when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);

        verify(messageStatusMapper).transitionStatus(
                eq(msgId),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.FAILED,
                        MqMessageStatusConstant.CONSUME_FAILED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.CONSUMED),
                isNull());
    }

    @Test
    void handleNotificationMessage_ShouldAckWhenStatusUpdateFailsAfterBusinessSuccess() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper);
        Message message = createTestMessage("test-msg-status-failure",
                "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doThrow(new RuntimeException("status store unavailable"))
                .when(messageStatusMapper).transitionStatus(anyString(), anyList(), anyInt(), any());

        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper).insert(any());
        verify(channel).basicAck(0L, false);
        verify(rabbitMQSender, never()).sendToRetryQueue(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void handleNotificationMessage_ShouldUpdateStatusOnFailure() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
            notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        String msgId = "test-msg-789";
        String jsonBody = "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}";
        Message message = createTestMessage(msgId, jsonBody);
        
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doThrow(new RuntimeException("DB error")).when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);

        verify(messageStatusMapper).transitionStatus(
                eq(msgId),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.FAILED,
                        MqMessageStatusConstant.CONSUME_FAILED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.CONSUME_FAILED),
                anyString());
        verify(channel).basicNack(0L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void handleNotificationMessage_ShouldAckCompletedDuplicateWithoutProcessing() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        Message message = createTestMessage("test-msg-completed",
                "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(2L);

        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper, never()).insert(any());
        verify(rabbitMQSender, never()).sendToRetryQueue(anyString(), anyString(), any(), anyInt());
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handleNotificationMessage_ShouldDeferMessageAlreadyBeingProcessed() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        Message message = createTestMessage("test-msg-processing",
                "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}");
        message.getMessageProperties().setHeader("x-retry-count", 2);
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(0L);
        when(rabbitMQSender.deferToRetryQueue(anyString(), anyString(), any(), eq(2))).thenReturn(true);

        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper, never()).insert(any());
        verify(rabbitMQSender).deferToRetryQueue(anyString(), anyString(), any(), eq(2));
        verify(rabbitMQSender, never()).sendToRetryQueue(anyString(), anyString(), any(), anyInt());
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handleNotificationMessage_ShouldRequeueWhenProcessingDeferralFails() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        Message message = createTestMessage("test-msg-processing-requeue",
                "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(0L);
        when(rabbitMQSender.deferToRetryQueue(anyString(), anyString(), any(), eq(0))).thenReturn(false);

        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper, never()).insert(any());
        verify(channel).basicNack(0L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void handleNotificationMessage_ShouldReleaseClaimAndProcessRetryAfterFailure() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        Message message = createTestMessage("test-msg-retry",
                "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        when(rabbitMQSender.sendToRetryQueue(anyString(), anyString(), any(), eq(1))).thenReturn(true);
        doThrow(new RuntimeException("DB error"))
                .doNothing()
                .when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);
        consumer.handleNotificationMessage(message, channel);

        verify(notificationMapper, times(2)).insert(any());
        verify(redisUtil, times(4)).evalStrict(anyString(), anyList(), anyList());
        verify(channel, times(2)).basicAck(0L, false);
    }

    @Test
    void handleNotificationMessage_ShouldBindHeadersAndRestorePreviousContext() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        MDC.put("requestId", "previous-request");
        MDC.put("userId", "previous-user");
        MDC.put("clientIp", "previous-ip");

        Message message = createTestMessage("test-msg-context", "{\"userId\":100,\"senderId\":200,\"notificationType\":1}");
        message.getMessageProperties().setHeader("requestId", "request-42");
        message.getMessageProperties().setHeader("userId", "user-100");
        message.getMessageProperties().setHeader("clientIp", "192.0.2.10");

        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doAnswer(invocation -> {
            assertEquals("request-42", MDC.get("requestId"));
            assertEquals("user-100", MDC.get("userId"));
            assertEquals("192.0.2.10", MDC.get("clientIp"));
            return null;
        }).when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);

        assertEquals("previous-request", MDC.get("requestId"));
        assertEquals("previous-user", MDC.get("userId"));
        assertEquals("previous-ip", MDC.get("clientIp"));
    }

    @Test
    void handleNotificationMessage_ShouldNotReuseMissingHeadersFromPreviousMessage() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
                notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        MDC.put("userId", "stale-user");
        MDC.put("clientIp", "stale-ip");

        Message message = createTestMessage("test-msg-missing-context", "{\"userId\":100,\"senderId\":200,\"notificationType\":1}");
        when(redisUtil.evalStrict(anyString(), anyList(), anyList())).thenReturn(1L);
        doAnswer(invocation -> {
            assertEquals("test-msg-missing-context", MDC.get("requestId"));
            assertNull(MDC.get("userId"));
            assertNull(MDC.get("clientIp"));
            return null;
        }).when(notificationMapper).insert(any());

        consumer.handleNotificationMessage(message, channel);
    }

    private Message createTestMessage(String msgId, String body) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(msgId);
        props.setHeader("eventType", "notification.created");
        props.setHeader("eventVersion", 1);
        return new Message(body.getBytes(), props);
    }
}
