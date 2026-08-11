package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

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
        
        when(redisUtil.setnx(eq("mq:idempotent:notification:" + msgId), anyString())).thenReturn(1L);
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
        
        when(redisUtil.setnx(anyString(), anyString())).thenReturn(1L);
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
    void handleNotificationMessage_ShouldUpdateStatusOnFailure() throws Exception {
        NotificationConsumer consumer = new NotificationConsumer(
            notificationMapper, redisUtil, rabbitMQSender, messageStatusMapper
        );

        String msgId = "test-msg-789";
        String jsonBody = "{\"userId\":100,\"senderId\":200,\"notificationType\":1,\"content\":\"test\"}";
        Message message = createTestMessage(msgId, jsonBody);
        
        when(redisUtil.setnx(anyString(), anyString())).thenReturn(1L);
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

    private Message createTestMessage(String msgId, String body) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(msgId);
        props.setHeader("eventType", "notification.created");
        props.setHeader("eventVersion", 1);
        return new Message(body.getBytes(), props);
    }
}
