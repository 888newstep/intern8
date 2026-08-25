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
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractMqConsumer {

    private static final long IDEMPOTENCY_PROCESSING_LEASE_SECONDS = 300L;
    private static final String CLAIM_IDEMPOTENCY_SCRIPT =
            "local state = redis.call('GET', KEYS[1]) " +
            "if not state then " +
            "redis.call('SET', KEYS[1], 'P:' .. ARGV[1], 'EX', ARGV[2], 'NX') " +
            "return 1 end " +
            "if state == '1' or state == 'DONE' then return 2 end " +
            "return 0";
    private static final String COMPLETE_IDEMPOTENCY_SCRIPT =
            "if redis.call('GET', KEYS[1]) ~= 'P:' .. ARGV[1] then return 0 end " +
            "redis.call('SET', KEYS[1], '1', 'EX', ARGV[2]) " +
            "return 1";
    private static final String RELEASE_IDEMPOTENCY_SCRIPT =
            "if redis.call('GET', KEYS[1]) == 'P:' .. ARGV[1] then " +
            "return redis.call('DEL', KEYS[1]) end " +
            "return 0";

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final RedisUtil redisUtil;
    protected final RabbitMQSender rabbitMQSender;
    private final MqMessageStatusMapper messageStatusMapper;
    protected static final String REQUEST_ID_HEADER = "requestId";
    protected static final String USER_ID_HEADER = "userId";
    protected static final String CLIENT_IP_HEADER = "clientIp";
    protected static final String EVENT_TYPE_HEADER = "eventType";
    protected static final String MESSAGE_ID_KEY = "messageId";
    private static final List<Integer> CONSUMER_MUTABLE_STATES = List.of(
            MqMessageStatusConstant.PENDING,
            MqMessageStatusConstant.CONFIRMED,
            MqMessageStatusConstant.FAILED,
            MqMessageStatusConstant.CONSUME_FAILED,
            MqMessageStatusConstant.COMPENSATING
    );

    protected AbstractMqConsumer(RedisUtil redisUtil, RabbitMQSender rabbitMQSender,
                                 MqMessageStatusMapper messageStatusMapper) {
        this.redisUtil = redisUtil;
        this.rabbitMQSender = rabbitMQSender;
        this.messageStatusMapper = messageStatusMapper;
    }

    protected final <T extends BaseMqEvent> void consume(
            Message amqpMessage,
            Channel channel,
            ConsumerDefinition definition,
            MessageDeserializer<T> deserializer,
            MessageProcessor<T> processor) {
        Map<String, String> previousMdc = bindMessageContext(amqpMessage);
        String msgId = getMessageId(amqpMessage);
        long deliveryTag = getDeliveryTag(amqpMessage);
        T event = null;
        IdempotencyClaim claim = IdempotencyClaim.untracked();

        try {
            event = deserializer.deserialize(amqpMessage);
            msgId = resolveEventId(event, amqpMessage);
            claim = claimIdempotency(definition.idempotencyPrefix(), msgId);
            if (acknowledgeIfCompleted(claim, channel, deliveryTag, definition.messageType(), msgId)) {
                return;
            }
            if (claim.isProcessing()) {
                logger.warn("{} message is already processing; defer through retry queue, msgId: {}",
                        definition.messageType(), msgId);
                deferProcessingMessage(event, channel, amqpMessage,
                        definition.dlxExchange(), definition.retryRoutingKey(), definition.messageType());
                return;
            }

            Object businessId = processor.process(event);
            markProcessed(claim, definition.idempotencyExpireSeconds(), msgId);
            transitionMessageStatus(msgId, MqMessageStatusConstant.CONSUMED, null);
            ack(channel, deliveryTag);
            logger.info("{} message processed, msgId: {}, businessId: {}",
                    definition.messageType(), msgId, businessId);
        } catch (Exception exception) {
            releaseIdempotency(claim, msgId);
            logger.error("Failed to process {} message, msgId: {}, payloadBytes: {}",
                    definition.messageType(), msgId, bodyLength(amqpMessage), exception);
            transitionMessageStatus(msgId, MqMessageStatusConstant.CONSUME_FAILED, exception.getMessage());
            handleRetryOrDlq(event, channel, amqpMessage,
                    definition.dlxExchange(), definition.retryRoutingKey(), definition.messageType());
        } finally {
            restoreMessageContext(previousMdc);
        }
    }

    private void transitionMessageStatus(String msgId, int status, String error) {
        if (msgId == null || msgId.isBlank()) {
            return;
        }
        try {
            messageStatusMapper.transitionStatus(msgId, CONSUMER_MUTABLE_STATES, status, error);
        } catch (Exception exception) {
            logger.warn("Failed to transition consumed MQ status, msgId: {}, targetStatus: {}",
                    msgId, status, exception);
        }
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

    protected IdempotencyClaim claimIdempotency(String prefix, String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return IdempotencyClaim.untracked();
        }

        String completedKey = prefix + msgId;
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        Object result = redisUtil.evalStrict(
                CLAIM_IDEMPOTENCY_SCRIPT,
                List.of(completedKey),
                List.of(ownerToken, String.valueOf(IDEMPOTENCY_PROCESSING_LEASE_SECONDS))
        );
        long state = toLong(result, "claim idempotency");
        if (state == 1L) {
            return IdempotencyClaim.acquired(completedKey, ownerToken);
        }
        if (state == 2L) {
            return IdempotencyClaim.completed(completedKey);
        }
        if (state == 0L) {
            return IdempotencyClaim.processing(completedKey);
        }
        throw new IllegalStateException("Unexpected Redis idempotency claim result: " + state);
    }

    protected boolean acknowledgeIfCompleted(IdempotencyClaim claim, Channel channel,
                                             long deliveryTag, String messageType, String msgId) throws Exception {
        if (!claim.isCompleted()) {
            return false;
        }
        logger.warn("Completed duplicate {} message ignored, msgId: {}", messageType, msgId);
        ack(channel, deliveryTag);
        return true;
    }

    protected void markProcessed(IdempotencyClaim claim, long expireSeconds, String msgId) {
        if (!claim.isAcquired()) {
            return;
        }
        try {
            Object result = redisUtil.evalStrict(
                    COMPLETE_IDEMPOTENCY_SCRIPT,
                    List.of(claim.completedKey),
                    List.of(claim.ownerToken, String.valueOf(expireSeconds))
            );
            if (toLong(result, "complete idempotency") != 1L) {
                logger.error("Idempotency processing lease was lost after business success, msgId: {}", msgId);
            }
        } catch (Exception exception) {
            // The business side effect has already succeeded. Retrying solely because Redis
            // finalization failed would create a duplicate, so acknowledge and alert instead.
            logger.error("Failed to finalize idempotency marker after business success, msgId: {}", msgId, exception);
        }
    }

    protected void releaseIdempotency(IdempotencyClaim claim, String msgId) {
        if (!claim.isAcquired()) {
            return;
        }
        try {
            redisUtil.evalStrict(
                    RELEASE_IDEMPOTENCY_SCRIPT,
                    List.of(claim.completedKey),
                    List.of(claim.ownerToken)
            );
        } catch (Exception exception) {
            logger.error("Failed to release idempotency processing lease, msgId: {}", msgId, exception);
        }
    }

    private long toLong(Object value, String operation) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Fall through to the diagnostic exception below.
            }
        }
        throw new IllegalStateException("Redis returned an invalid result for " + operation + ": " + value);
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

    protected void deferProcessingMessage(BaseMqEvent event, Channel channel, Message amqpMessage,
                                          String dlxExchange, String retryRoutingKey, String msgType) {
        String msgId = getMessageId(amqpMessage);
        long deliveryTag = getDeliveryTag(amqpMessage);
        int retryCount = getRetryCount(amqpMessage);

        try {
            boolean accepted = rabbitMQSender.deferToRetryQueue(
                    dlxExchange, retryRoutingKey, event, retryCount);
            if (accepted) {
                ack(channel, deliveryTag);
                logger.warn("{} message is already processing and was deferred without consuming retry budget, msgId: {}",
                        msgType, msgId);
                return;
            }
            requeue(channel, deliveryTag, msgType, msgId);
        } catch (Exception exception) {
            logger.error("Failed to defer processing {} message, msgId: {}", msgType, msgId, exception);
            try {
                requeue(channel, deliveryTag, msgType, msgId);
            } catch (Exception requeueException) {
                logger.error("Failed to requeue processing message, msgId: {}", msgId, requeueException);
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

    private void requeue(Channel channel, long deliveryTag, String msgType, String msgId) throws Exception {
        channel.basicNack(deliveryTag, false, true);
        logger.warn("{} message requeued because deferred publish was not accepted, msgId: {}", msgType, msgId);
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

        // 先清理消息作用域字段，避免缺失 Header 时沿用 listener 线程上的旧值。
        MDC.remove(REQUEST_ID_HEADER);
        MDC.remove(USER_ID_HEADER);
        MDC.remove(CLIENT_IP_HEADER);
        MDC.remove(EVENT_TYPE_HEADER);
        MDC.remove(MESSAGE_ID_KEY);

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

    protected record ConsumerDefinition(String messageType,
                                        String idempotencyPrefix,
                                        long idempotencyExpireSeconds,
                                        String dlxExchange,
                                        String retryRoutingKey) {
    }

    @FunctionalInterface
    protected interface MessageDeserializer<T extends BaseMqEvent> {
        T deserialize(Message message) throws Exception;
    }

    @FunctionalInterface
    protected interface MessageProcessor<T extends BaseMqEvent> {
        Object process(T event) throws Exception;
    }

    protected static final class IdempotencyClaim {
        private final ClaimState state;
        private final String completedKey;
        private final String ownerToken;

        private IdempotencyClaim(ClaimState state, String completedKey, String ownerToken) {
            this.state = state;
            this.completedKey = completedKey;
            this.ownerToken = ownerToken;
        }

        private static IdempotencyClaim acquired(String completedKey, String ownerToken) {
            return new IdempotencyClaim(ClaimState.ACQUIRED, completedKey, ownerToken);
        }

        private static IdempotencyClaim completed(String completedKey) {
            return new IdempotencyClaim(ClaimState.COMPLETED, completedKey, null);
        }

        private static IdempotencyClaim processing(String completedKey) {
            return new IdempotencyClaim(ClaimState.PROCESSING, completedKey, null);
        }

        protected static IdempotencyClaim untracked() {
            return new IdempotencyClaim(ClaimState.UNTRACKED, null, null);
        }

        protected boolean isAcquired() {
            return state == ClaimState.ACQUIRED;
        }

        protected boolean isCompleted() {
            return state == ClaimState.COMPLETED;
        }

        protected boolean isProcessing() {
            return state == ClaimState.PROCESSING;
        }
    }

    private enum ClaimState {
        ACQUIRED,
        COMPLETED,
        PROCESSING,
        UNTRACKED
    }
}
