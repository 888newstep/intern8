package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.ArchiveDynamicEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;

import java.util.Date;
import java.util.List;

@Service
public class RabbitMQSender {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQSender.class);
    private static final String REQUEST_ID_HEADER = "requestId";
    private static final String USER_ID_HEADER = "userId";
    private static final String CLIENT_IP_HEADER = "clientIp";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final RabbitTemplate rabbitTemplate;
    private final MqMessageStatusMapper messageStatusMapper;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerService circuitBreakerService;

    @Autowired
    public RabbitMQSender(RabbitTemplate rabbitTemplate,
                          MqMessageStatusMapper messageStatusMapper,
                          ObjectMapper objectMapper,
                          CircuitBreakerService circuitBreakerService) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageStatusMapper = messageStatusMapper;
        this.objectMapper = objectMapper;
        this.circuitBreakerService = circuitBreakerService;
    }

    public RabbitMQSender(RabbitTemplate rabbitTemplate,
                          MqMessageStatusMapper messageStatusMapper,
                          ObjectMapper objectMapper) {
        this(rabbitTemplate, messageStatusMapper, objectMapper, null);
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String msgId = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                logger.debug("Message confirmed successfully, msgId: {}", msgId);
                if (correlationData != null) {
                    transitionStatus(msgId,
                            List.of(MqMessageStatusConstant.PENDING, MqMessageStatusConstant.COMPENSATING),
                            MqMessageStatusConstant.CONFIRMED,
                            null);
                }
            } else {
                if (circuitBreakerService != null) {
                    circuitBreakerService.recordMqFailure(new IllegalStateException(
                            cause == null ? "RabbitMQ publisher confirm nack" : cause));
                }
                logger.error("Message confirmation failed, msgId: {}, cause: {}", msgId, cause);
                if (correlationData != null) {
                    markPublishFailed(msgId, cause);
                }
            }
        });

        rabbitTemplate.setReturnsCallback(returned -> {
            if (returned == null || returned.getMessage() == null) {
                logger.warn("RabbitMQ returned callback did not contain a message");
                return;
            }
            String msgId = returned.getMessage().getMessageProperties().getMessageId();
            logger.warn("Message returned: exchange={}, routingKey={}, replyCode={}, replyText={}, msgId={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(),
                    returned.getReplyText(), msgId);
            markPublishFailed(msgId, "Message returned: " + returned.getReplyText());
        });
    }

    public boolean sendNotificationMessage(NotificationEvent event) {
        if (!persistInitialMessage(event)) {
            return false;
        }
        boolean accepted = sendEvent(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_ROUTING_KEY, event, 0, event.getEventId());
        logger.debug("Notification event publish attempted, eventId: {}, userId: {}, accepted={}",
                event.getEventId(), event.getUserId(), accepted);
        return accepted;
    }

    public boolean sendArchiveMessage(ArchiveDynamicEvent event) {
        if (!persistInitialMessage(event)) {
            return false;
        }
        boolean accepted = sendEvent(RabbitMQConfig.ARCHIVE_DELAY_EXCHANGE,
                RabbitMQConfig.ARCHIVE_DELAY_ROUTING_KEY, event, 0, event.getEventId());
        logger.info("Archive delay publish attempted, dynamicId: {}, eventId: {}, accepted={}",
                event.getDynamicId(), event.getEventId(), accepted);
        return accepted;
    }

    /**
     * Publishes an event loaded from the transactional outbox. The outbox
     * relay owns the row state; this sender continues to own the RabbitMQ
     * confirm/return and mq_message_status state machine.
     */
    public boolean publishOutboxMessage(MqOutbox outbox) {
        if (outbox == null || isBlank(outbox.getEventId()) || isBlank(outbox.getEventType())
                || isBlank(outbox.getExchangeName()) || isBlank(outbox.getRoutingKey())
                || isBlank(outbox.getMessageBody())) {
            throw new IllegalArgumentException("MQ outbox row is incomplete");
        }

        BaseMqEvent event = deserializeEvent(outbox.getEventType(), outbox.getMessageBody(), outbox.getEventId());
        if (event == null || !outbox.getEventId().equals(event.getEventId())) {
            throw new IllegalArgumentException("MQ outbox payload cannot be decoded or event ID mismatches");
        }
        if (!persistInitialMessage(event)) {
            return false;
        }

        int retryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        return sendEvent(outbox.getExchangeName(), outbox.getRoutingKey(), event, retryCount, outbox.getEventId());
    }

    /**
     * Publishes a retry message and reports whether the local RabbitTemplate
     * accepted it. A true result is not a broker confirm; the asynchronous
     * confirm/return callbacks still decide the persisted publish state.
     */
    public boolean sendToRetryQueue(String exchange, String routingKey, BaseMqEvent event, int retryCount) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            logger.warn("Skip retry publish because event or eventId is missing");
            return false;
        }
        boolean accepted = sendEvent(exchange, routingKey, event, retryCount, event.getEventId());
        if (accepted) {
            incrementRetryCount(event.getEventId());
        }
        logger.info("Retry queue publish attempted, eventId: {}, retryCount: {}, accepted={}",
                event.getEventId(), retryCount, accepted);
        return accepted;
    }

    public boolean republishFailedMessage(MqMessageStatus messageStatus) {
        if (messageStatus == null || messageStatus.getMessageId() == null
                || messageStatus.getMessageId().isBlank()) {
            return false;
        }
        int claimed = claimForCompensation(messageStatus.getMessageId());
        if (claimed == 0) {
            logger.debug("Skip compensation because message is already claimed or no longer eligible, msgId: {}",
                    messageStatus.getMessageId());
            return false;
        }

        BaseMqEvent event = deserializeEvent(messageStatus);
        if (event == null) {
            logger.warn("Skip compensation message because payload cannot be decoded, msgId: {}", messageStatus.getMessageId());
            markCompensationDeadLettered(messageStatus.getMessageId(), "Message payload cannot be decoded");
            return false;
        }

        Route route = resolveOriginalRoute(messageStatus.getEventType());
        if (route == null) {
            logger.warn("Skip compensation message because route is unknown, msgId: {}, eventType: {}",
                    messageStatus.getMessageId(), messageStatus.getEventType());
            markCompensationDeadLettered(messageStatus.getMessageId(),
                    "Unknown event route: " + messageStatus.getEventType());
            return false;
        }

        int currentRetryCount = messageStatus.getRetryCount() == null ? 0 : messageStatus.getRetryCount();
        int nextRetryCount = currentRetryCount + 1;
        boolean accepted = sendEvent(route.exchange, route.routingKey, event, nextRetryCount,
                messageStatus.getMessageId());
        if (!accepted) {
            markCompensationFailed(messageStatus.getMessageId(), "Retry publish was rejected locally");
            return false;
        }
        incrementRetryCount(messageStatus.getMessageId());
        logger.info("Compensation message resent, msgId: {}, eventType: {}", messageStatus.getMessageId(), messageStatus.getEventType());
        return true;
    }

    private boolean persistInitialMessage(BaseMqEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            logger.warn("Cannot persist MQ status because event or eventId is missing");
            return false;
        }
        try {
            MqMessageStatus status = new MqMessageStatus();
            status.setMessageId(event.getEventId());
            status.setEventType(event.getEventType());
            status.setBusinessKey(event.getEventId());
            status.setMessageBody(objectMapper.writeValueAsString(event));
            status.setStatus(MqMessageStatusConstant.PENDING);
            status.setRetryCount(0);
            status.setCreateTime(new Date());
            status.setUpdateTime(new Date());
            messageStatusMapper.insert(status);
            return true;
        } catch (DuplicateKeyException e) {
            logger.warn("Message status already exists, eventId: {}", event.getEventId());
            return true;
        } catch (Exception e) {
            logger.error("Failed to record MQ status; publish is skipped to preserve compensation ability, eventId: {}",
                    event.getEventId(), e);
            return false;
        }
    }

    private boolean sendEvent(String exchange, String routingKey, BaseMqEvent event, int retryCount, String messageId) {
        if (circuitBreakerService == null) {
            try {
                publishEvent(exchange, routingKey, event, retryCount, messageId);
                return true;
            } catch (Exception e) {
                markPublishFailed(messageId, e.getMessage());
                return false;
            }
        }

        return circuitBreakerService.executeWithMqBreaker(
                () -> {
                    publishEvent(exchange, routingKey, event, retryCount, messageId);
                    return true;
                },
                () -> {
                    markPublishFailed(messageId, "RabbitMQ circuit breaker is open");
                    return false;
                }
        );
    }

    private void markPublishFailed(String messageId, Throwable cause) {
        String error = cause == null ? "RabbitMQ publisher confirm nack" : cause.getMessage();
        markPublishFailed(messageId, error);
    }

    private void markPublishFailed(String messageId, String error) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        transitionStatus(messageId,
                List.of(MqMessageStatusConstant.PENDING,
                        MqMessageStatusConstant.CONFIRMED,
                        MqMessageStatusConstant.COMPENSATING),
                MqMessageStatusConstant.FAILED,
                normalizeError(error));
    }

    private void markCompensationFailed(String messageId, String error) {
        transitionStatus(messageId,
                List.of(MqMessageStatusConstant.COMPENSATING),
                MqMessageStatusConstant.FAILED,
                normalizeError(error));
    }

    private void markCompensationDeadLettered(String messageId, String error) {
        transitionStatus(messageId,
                List.of(MqMessageStatusConstant.COMPENSATING),
                MqMessageStatusConstant.DEAD_LETTERED,
                normalizeError(error));
    }

    private int claimForCompensation(String messageId) {
        try {
            return messageStatusMapper.claimForCompensation(messageId,
                    RabbitMQConfig.MAX_RETRY_COUNT,
                    MqMessageStatusConstant.COMPENSATING_STALE_SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to claim message for compensation, msgId: {}", messageId, e);
            return 0;
        }
    }

    private void incrementRetryCount(String messageId) {
        try {
            messageStatusMapper.incrementRetryCount(messageId, null);
        } catch (Exception e) {
            logger.warn("Failed to increment retry count, msgId: {}", messageId, e);
        }
    }

    private void transitionStatus(String messageId, List<Integer> fromStatuses,
                                  int toStatus, String lastError) {
        try {
            int updated = messageStatusMapper.transitionStatus(messageId, fromStatuses, toStatus, lastError);
            if (updated == 0) {
                logger.debug("MQ status transition skipped because state changed, msgId: {}, targetStatus: {}",
                        messageId, toStatus);
            }
        } catch (Exception e) {
            logger.warn("Failed to transition MQ status, msgId: {}, targetStatus: {}", messageId, toStatus, e);
        }
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown MQ failure";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private void publishEvent(String exchange, String routingKey, BaseMqEvent event, int retryCount, String messageId) {
        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                event,
                message -> applyMessageMetadata(message, event, retryCount, messageId),
                new CorrelationData(messageId)
        );
    }

    private Message applyMessageMetadata(Message message, BaseMqEvent event, int retryCount, String messageId) {
        message.getMessageProperties().setMessageId(messageId);
        message.getMessageProperties().setHeader("eventType", event.getEventType());
        message.getMessageProperties().setHeader("eventVersion", event.getEventVersion());
        message.getMessageProperties().setHeader("x-retry-count", retryCount);
        copyMdcHeader(message, REQUEST_ID_HEADER);
        copyMdcHeader(message, USER_ID_HEADER);
        copyMdcHeader(message, CLIENT_IP_HEADER);
        return message;
    }

    private void copyMdcHeader(Message message, String key) {
        String value = MDC.get(key);
        if (value != null && !value.isBlank()) {
            message.getMessageProperties().setHeader(key, value);
        }
    }

    private BaseMqEvent deserializeEvent(MqMessageStatus messageStatus) {
        return deserializeEvent(messageStatus.getEventType(), messageStatus.getMessageBody(), messageStatus.getMessageId());
    }

    private BaseMqEvent deserializeEvent(String eventType, String messageBody, String messageId) {
        try {
            if (messageBody == null || messageBody.isBlank()) {
                return null;
            }
            if (NotificationEvent.EVENT_TYPE.equals(eventType)) {
                return objectMapper.readValue(messageBody, NotificationEvent.class);
            }
            if (ArchiveDynamicEvent.EVENT_TYPE.equals(eventType)) {
                return objectMapper.readValue(messageBody, ArchiveDynamicEvent.class);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to deserialize message body, msgId: {}", messageId, e);
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Route resolveOriginalRoute(String eventType) {
        if (NotificationEvent.EVENT_TYPE.equals(eventType)) {
            return new Route(RabbitMQConfig.NOTIFICATION_EXCHANGE, RabbitMQConfig.NOTIFICATION_ROUTING_KEY);
        }
        if (ArchiveDynamicEvent.EVENT_TYPE.equals(eventType)) {
            return new Route(RabbitMQConfig.ARCHIVE_DELAY_EXCHANGE, RabbitMQConfig.ARCHIVE_DELAY_ROUTING_KEY);
        }
        return null;
    }

    private static final class Route {
        private final String exchange;
        private final String routingKey;

        private Route(String exchange, String routingKey) {
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
    }
}
