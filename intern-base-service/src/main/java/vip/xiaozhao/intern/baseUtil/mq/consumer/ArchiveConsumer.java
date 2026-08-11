package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.util.Map;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.ArchiveDynamicEvent;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;


@Component
public class ArchiveConsumer extends AbstractMqConsumer {

    private static final String IDEMPOTENT_PREFIX = "mq:idempotent:archive:";
    private static final long IDEMPOTENT_EXPIRE = 15 * 24 * 60 * 60L;
    private static final String MESSAGE_TYPE = "archive";

    private final TuiDynamicMapper dynamicMapper;
    private final MqMessageStatusMapper messageStatusMapper;

    public ArchiveConsumer(TuiDynamicMapper dynamicMapper,
                           RedisUtil redisUtil,
                           RabbitMQSender rabbitMQSender,
                           MqMessageStatusMapper messageStatusMapper) {
        super(redisUtil, rabbitMQSender);
        this.dynamicMapper = dynamicMapper;
        this.messageStatusMapper = messageStatusMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.ARCHIVE_QUEUE)
    public void handleArchiveMessage(Message amqpMessage, Channel channel) {
        Map<String, String> previousMdc = bindMessageContext(amqpMessage);
        String msgId = getMessageId(amqpMessage);
        long deliveryTag = getDeliveryTag(amqpMessage);
        ArchiveDynamicEvent event = null;

        try {
            event = deserializeArchiveEvent(amqpMessage);
            msgId = resolveEventId(event, amqpMessage);
            if (ackIfDuplicate(channel, deliveryTag, IDEMPOTENT_PREFIX, msgId, IDEMPOTENT_EXPIRE, MESSAGE_TYPE)) {
                return;
            }

            logger.info("Archiving dynamic: dynamicId={}, msgId={}", event.getDynamicId(), msgId);
            dynamicMapper.archiveById(event.getDynamicId());
            markProcessed(IDEMPOTENT_PREFIX, msgId, IDEMPOTENT_EXPIRE);
            if (msgId != null) {
                messageStatusMapper.transitionStatus(msgId,
                        CONSUMER_MUTABLE_STATES,
                        MqMessageStatusConstant.CONSUMED,
                        null);
            }

            ack(channel, deliveryTag);
            logger.info("Dynamic archived, dynamicId: {}, msgId: {}", event.getDynamicId(), msgId);
        } catch (Exception e) {
            logger.error("Failed to archive dynamic, msgId: {}, payloadBytes: {}",
                    msgId, bodyLength(amqpMessage), e);
            if (msgId != null) {
                messageStatusMapper.transitionStatus(msgId,
                        CONSUMER_MUTABLE_STATES,
                        MqMessageStatusConstant.CONSUME_FAILED,
                        e.getMessage());
            }
            handleRetryOrDlq(event, channel, amqpMessage,
                    RabbitMQConfig.ARCHIVE_DLX_EXCHANGE,
                    RabbitMQConfig.ARCHIVE_RETRY_ROUTING_KEY,
                    MESSAGE_TYPE);
        } finally {
            restoreMessageContext(previousMdc);
        }
    }

    private ArchiveDynamicEvent deserializeArchiveEvent(Message amqpMessage) throws Exception {
        String body = bodyAsString(amqpMessage).trim();
        ArchiveDynamicEvent event;
        if (body.startsWith("{")) {
            JsonNode root = objectMapper.readTree(body);
            event = hasEventMetadata(root)
                    ? objectMapper.treeToValue(root, ArchiveDynamicEvent.class)
                    : deserializeLegacyArchive(root);
        } else {
            event = new ArchiveDynamicEvent();
            event.setDynamicId(Long.parseLong(body));
        }
        enrichBaseFields(event, amqpMessage, ArchiveDynamicEvent.EVENT_TYPE, ArchiveDynamicEvent.EVENT_VERSION);
        return event;
    }

    private ArchiveDynamicEvent deserializeLegacyArchive(JsonNode root) {
        ArchiveDynamicEvent event = new ArchiveDynamicEvent();
        event.setDynamicId(root.path("dynamicId").longValue());
        return event;
    }
}
