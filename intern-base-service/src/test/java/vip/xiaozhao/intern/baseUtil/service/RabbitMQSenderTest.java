package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.slf4j.MDC;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitMQSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MqMessageStatusMapper messageStatusMapper;

    @Test
    void sendToRetryQueue_ShouldNotAcknowledgePublishWhenLocalSendFails() {
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");

        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        assertFalse(sender.sendToRetryQueue(
                RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                event,
                1));

        verify(messageStatusMapper, never()).incrementRetryCount(event.getEventId(), null);
        verify(messageStatusMapper).transitionStatus(
                eq(event.getEventId()),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.FAILED),
                startsWith("broker unavailable"));
    }

    @Test
    void sendToRetryQueue_ShouldIncrementRetryCountAfterLocalAcceptance() {
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");

        assertTrue(sender.sendToRetryQueue(
                RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                event,
                1));

        verify(messageStatusMapper).incrementRetryCount(event.getEventId(), null);
    }

    @Test
    void deferToRetryQueue_ShouldPreserveRetryCountWithoutIncrementingPersistence() throws Exception {
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        AtomicReference<MessagePostProcessor> postProcessor = new AtomicReference<>();
        doAnswer(invocation -> {
            postProcessor.set(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        assertTrue(sender.deferToRetryQueue(
                RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                event,
                2));

        Message message = postProcessor.get().postProcessMessage(
                new Message("payload".getBytes(), new MessageProperties()));
        assertEquals(2, message.getMessageProperties().getHeaders().get("x-retry-count"));
        verify(messageStatusMapper, never()).incrementRetryCount(event.getEventId(), null);
    }

    @Test
    void publishOutboxMessage_ShouldReturnTrueOnlyAfterBrokerAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, objectMapper, 100L);
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        MqOutbox outbox = createOutbox(event, objectMapper);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        assertTrue(sender.publishOutboxMessage(outbox));

        verify(messageStatusMapper).transitionStatus(
                eq(event.getEventId()),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.FAILED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.CONFIRMED),
                eq(null));
    }

    @Test
    void publishOutboxMessage_ShouldReturnFalseOnBrokerNack() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, objectMapper, 100L);
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        MqOutbox outbox = createOutbox(event, objectMapper);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        assertFalse(sender.publishOutboxMessage(outbox));

        verify(messageStatusMapper).transitionStatus(
                eq(event.getEventId()),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.FAILED),
                startsWith("RabbitMQ publisher confirm nack: broker nack"));
    }

    @Test
    void publishOutboxMessage_ShouldReturnFalseWhenMessageIsReturned() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, objectMapper, 100L);
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        MqOutbox outbox = createOutbox(event, objectMapper);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(event.getEventId());
            correlationData.setReturned(new ReturnedMessage(
                    new Message("payload".getBytes(), properties),
                    312,
                    "NO_ROUTE",
                    outbox.getExchangeName(),
                    outbox.getRoutingKey()));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        assertFalse(sender.publishOutboxMessage(outbox));

        verify(messageStatusMapper).transitionStatus(
                eq(event.getEventId()),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.FAILED),
                startsWith("Message returned: NO_ROUTE"));
    }

    @Test
    void publishOutboxMessage_ShouldReturnFalseWhenConfirmTimesOut() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, objectMapper, 10L);
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        MqOutbox outbox = createOutbox(event, objectMapper);

        assertFalse(sender.publishOutboxMessage(outbox));

        verify(messageStatusMapper).transitionStatus(
                eq(event.getEventId()),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.FAILED),
                startsWith("RabbitMQ publisher confirm timed out after 10 ms"));
    }

    @Test
    void callbacks_ShouldUseConditionalStateTransitions() {
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, new ObjectMapper());
        AtomicReference<RabbitTemplate.ConfirmCallback> confirmCallback = new AtomicReference<>();
        AtomicReference<RabbitTemplate.ReturnsCallback> returnsCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            confirmCallback.set(invocation.getArgument(0));
            return null;
        }).when(rabbitTemplate).setConfirmCallback(any(RabbitTemplate.ConfirmCallback.class));
        doAnswer(invocation -> {
            returnsCallback.set(invocation.getArgument(0));
            return null;
        }).when(rabbitTemplate).setReturnsCallback(any(RabbitTemplate.ReturnsCallback.class));

        sender.init();

        confirmCallback.get().confirm(new CorrelationData("confirmed-msg"), true, null);
        verify(messageStatusMapper).transitionStatus(
                eq("confirmed-msg"),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.CONFIRMED),
                eq(null));

        MessageProperties properties = new MessageProperties();
        properties.setMessageId("returned-msg");
        returnsCallback.get().returnedMessage(new ReturnedMessage(
                new Message("payload".getBytes(), properties),
                312,
                "NO_ROUTE",
                "exchange",
                "routing-key"));
        verify(messageStatusMapper).transitionStatus(
                eq("returned-msg"),
                eq(List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING)),
                eq(MqMessageStatusConstant.FAILED),
                 startsWith("Message returned: NO_ROUTE"));
    }

    @Test
    void publish_ShouldPropagateRequestUserAndClientHeaders() throws Exception {
        RabbitMQSender sender = new RabbitMQSender(rabbitTemplate, messageStatusMapper, new ObjectMapper());
        NotificationEvent event = NotificationEvent.create(100L, 200L, 1, "content", "target");
        AtomicReference<MessagePostProcessor> postProcessor = new AtomicReference<>();

        doAnswer(invocation -> {
            postProcessor.set(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        MDC.put("requestId", "request-42");
        MDC.put("userId", "user-100");
        MDC.put("clientIp", "192.0.2.10");
        try {
            assertTrue(sender.sendToRetryQueue(
                    RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                    RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                    event,
                    2));

            assertNotNull(postProcessor.get());
            Message message = postProcessor.get().postProcessMessage(
                    new Message("payload".getBytes(), new MessageProperties()));

            assertEquals(event.getEventId(), message.getMessageProperties().getMessageId());
            assertEquals("request-42", message.getMessageProperties().getHeaders().get("requestId"));
            assertEquals("user-100", message.getMessageProperties().getHeaders().get("userId"));
            assertEquals("192.0.2.10", message.getMessageProperties().getHeaders().get("clientIp"));
            assertEquals(2, message.getMessageProperties().getHeaders().get("x-retry-count"));
        } finally {
            MDC.clear();
        }

        verify(messageStatusMapper, atLeastOnce()).incrementRetryCount(event.getEventId(), null);
    }

    private MqOutbox createOutbox(NotificationEvent event, ObjectMapper objectMapper) throws Exception {
        MqOutbox outbox = new MqOutbox();
        outbox.setId(1L);
        outbox.setEventId(event.getEventId());
        outbox.setEventType(event.getEventType());
        outbox.setExchangeName(RabbitMQConfig.NOTIFICATION_EXCHANGE);
        outbox.setRoutingKey(RabbitMQConfig.NOTIFICATION_ROUTING_KEY);
        outbox.setMessageBody(objectMapper.writeValueAsString(event));
        outbox.setRetryCount(0);
        return outbox;
    }
}
