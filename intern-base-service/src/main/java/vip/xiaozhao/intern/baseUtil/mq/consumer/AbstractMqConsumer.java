package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractMqConsumer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final RedisUtil redisUtil;
    protected final RabbitMQSender rabbitMQSender;
    protected static final String REQUEST_ID_HEADER = "requestId";
    protected static final String USER_ID_HEADER = "userId";
    protected static final String CLIENT_IP_HEADER = "clientIp";
    protected static final String EVENT_TYPE_HEADER = "eventType";
    protected static final String MESSAGE_ID_KEY = "messageId";
    protected static final List<Integer> CONSUMER_MUTABLE_STATES = List.of(
            MqMessageStatusConstant.PENDING,
            MqMessageStatusConstant.CONFIRMED,
            MqMessageStatusConstant.FAILED,
            MqMessageStatusConstant.CONSUME_FAILED,
            MqMessageStatusConstant.COMPENSATING
    );

    protected AbstractMqConsumer(RedisUtil redisUtil, RabbitMQSender rabbitMQSender) {
        this.redisUtil = redisUtil;
        this.rabbitMQSender = rabbitMQSender;
    }

    protected boolean hasEventMetadata(JsonNode root) {
        return root.hasNonNull("eventId") || root.hasNonNull("eventType");
    }

    protected void enrichBaseFields(BaseMqEvent event, Message amqpMessage, String defaultType, int defaultVersion) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            String msgId = getMessageId(amqpMessage);
            event.setEventId(msgId != null ? msgId : UUID.randomUUID().toString().replace("-", ""));
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            event.setEventType(defaultType);
        }
        if (event.getEventVersion() == null) {
            event.setEventVersion(resolveEventVersion(amqpMessage, defaultVersion));
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(Instant.now().toEpochMilli());
        }
    }

    private int resolveEventVersion(Message amqpMessage, int defaultVersion) {
        Object versionHeader = amqpMessage.getMessageProperties().getHeaders().get("eventVersion");
        if (versionHeader instanceof Number) {
            return ((Number) versionHeader).intValue();
        }
        return defaultVersion;
    }

    protected boolean ackIfDuplicate(Channel channel, long deliveryTag, String prefix,
                                   String msgId, long expireSeconds, String messageType) throws Exception {
        if (!isDuplicate(prefix, msgId, expireSeconds)) {
            return false;
        }
        logger.warn("Duplicate {} message ignored, msgId: {}", messageType, msgId);
        ack(channel, deliveryTag);
        return true;
    }

    private boolean isDuplicate(String prefix, String msgId, long expireSeconds) {
        if (msgId == null) {
            return false;
        }
        String key = prefix + msgId;
        Long result = redisUtil.setnx(key, "1");
        if (result != null && result == 1) {
            redisUtil.expire(key, expireSeconds);
            return false;
        }
        return true;
    }

    protected void markProcessed(String prefix, String msgId, long expireSeconds) {
        if (msgId != null) {
            redisUtil.set(prefix + msgId, "1", (int) expireSeconds);
        }
    }

    protected void handleRetryOrDlq(BaseMqEvent event, Channel channel, Message amqpMessage,
                                  String dlxExchange, String retryRoutingKey, String msgType) {
        String msgId = getMessageId(amqpMessage);
        long deliveryTag = getDeliveryTag(amqpMessage);
        int retryCount = getRetryCount(amqpMessage);

        try {
            if (event != null && retryCount < RabbitMQConfig.MAX_RETRY_COUNT) {
                int nextRetryCount = retryCount + 1;
                boolean accepted = rabbitMQSender.sendToRetryQueue(dlxExchange, retryRoutingKey,
                        event, nextRetryCount);
                if (accepted) {
                    ack(channel, deliveryTag);
                    logger.warn("{} message sent to retry queue, msgId: {}, retryCount: {}/{}",
                            msgType, msgId, nextRetryCount, RabbitMQConfig.MAX_RETRY_COUNT);
                } else {
                    // Do not acknowledge the original message when the retry
                    // publish was rejected; keep it available in the DLQ.
                    nackToDlq(channel, deliveryTag, msgType, msgId, retryCount);
                    logger.error("{} retry publish was rejected; original message moved to DLQ, msgId: {}",
                            msgType, msgId);
                }
                return;
            }

            nackToDlq(channel, deliveryTag, msgType, msgId, retryCount);
        } catch (Exception e) {
            logger.error("Failed to handle retry/DLQ for {} message, msgId: {}", msgType, msgId, e);
            try {
                nackToDlq(channel, deliveryTag, msgType, msgId, retryCount);
            } catch (Exception ex) {
                logger.error("Failed to nack message, msgId: {}", msgId, ex);
            }
        }
    }

    private int getRetryCount(Message amqpMessage) {
        Object retryHeader = amqpMessage.getMessageProperties().getHeader("x-retry-count");
        if (retryHeader instanceof Number) {
            return ((Number) retryHeader).intValue();
        }
        return 0;
    }

    protected void ack(Channel channel, long deliveryTag) throws Exception {
        channel.basicAck(deliveryTag, false);
    }

    private void nackToDlq(Channel channel, long deliveryTag, String msgType, String msgId, int retryCount) throws Exception {
        channel.basicNack(deliveryTag, false, false);
        logger.error("{} message sent to DLQ, msgId: {}, retryCount: {}", msgType, msgId, retryCount);
    }

    protected String resolveEventId(BaseMqEvent event, Message amqpMessage) {
        if (event != null && event.getEventId() != null) {
            return event.getEventId();
        }
        return getMessageId(amqpMessage);
    }

    protected String getMessageId(Message amqpMessage) {
        return amqpMessage.getMessageProperties().getMessageId();
    }

    protected long getDeliveryTag(Message amqpMessage) {
        return amqpMessage.getMessageProperties().getDeliveryTag();
    }

    protected String bodyAsString(Message amqpMessage) {
        return new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
    }

    protected int bodyLength(Message amqpMessage) {
        return amqpMessage == null || amqpMessage.getBody() == null
                ? 0
                : amqpMessage.getBody().length;
    }

    protected Map<String, String> bindMessageContext(Message amqpMessage) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        putHeaderIfPresent(amqpMessage, REQUEST_ID_HEADER);
        putHeaderIfPresent(amqpMessage, USER_ID_HEADER);
        putHeaderIfPresent(amqpMessage, CLIENT_IP_HEADER);
        putHeaderIfPresent(amqpMessage, EVENT_TYPE_HEADER);

        String messageId = getMessageId(amqpMessage);
        if (messageId != null && !messageId.isBlank()) {
            MDC.put(MESSAGE_ID_KEY, messageId);
            if (MDC.get(REQUEST_ID_HEADER) == null) {
                MDC.put(REQUEST_ID_HEADER, messageId);
            }
        }
        return previous;
    }

    protected void restoreMessageContext(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    private void putHeaderIfPresent(Message amqpMessage, String key) {
        Object value = amqpMessage.getMessageProperties().getHeaders().get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            MDC.put(key, String.valueOf(value));
        }
    }
}
