package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.ArchiveDynamicEvent;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;


@Component
public class ArchiveConsumer extends AbstractMqConsumer {

    private static final ConsumerDefinition DEFINITION = new ConsumerDefinition(
            "archive",
            "mq:idempotent:archive:",
            15 * 24 * 60 * 60L,
            RabbitMQConfig.ARCHIVE_DLX_EXCHANGE,
            RabbitMQConfig.ARCHIVE_RETRY_ROUTING_KEY);

    private final TuiDynamicMapper dynamicMapper;

    public ArchiveConsumer(TuiDynamicMapper dynamicMapper,
                           RedisUtil redisUtil,
                           RabbitMQSender rabbitMQSender,
                           MqMessageStatusMapper messageStatusMapper) {
        super(redisUtil, rabbitMQSender, messageStatusMapper);
        this.dynamicMapper = dynamicMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.ARCHIVE_QUEUE)
    public void handleArchiveMessage(Message amqpMessage, Channel channel) {
        consume(amqpMessage, channel, DEFINITION,
                this::deserializeArchiveEvent, this::archiveDynamic);
    }

    private Long archiveDynamic(ArchiveDynamicEvent event) {
        dynamicMapper.archiveById(event.getDynamicId());
        return event.getDynamicId();
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
