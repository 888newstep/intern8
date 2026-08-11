package vip.xiaozhao.intern.baseUtil.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiNotification;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiNotificationMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;
import vip.xiaozhao.intern.baseUtil.service.RabbitMQSender;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.Date;
import java.util.Map;

@Component
public class NotificationConsumer extends AbstractMqConsumer {

    private static final String IDEMPOTENT_PREFIX = "mq:idempotent:notification:";
    private static final long IDEMPOTENT_EXPIRE = 3 * 24 * 60 * 60L;
    private static final String UNREAD_COUNT_PREFIX = "notification:unread:count:";
    private static final String MESSAGE_TYPE = "notification";

    private final TuiNotificationMapper notificationMapper;
    private final MqMessageStatusMapper messageStatusMapper;

    public NotificationConsumer(TuiNotificationMapper notificationMapper,
                                RedisUtil redisUtil,
                                RabbitMQSender rabbitMQSender,
                                MqMessageStatusMapper messageStatusMapper) {
        super(redisUtil, rabbitMQSender);
        this.notificationMapper = notificationMapper;
        this.messageStatusMapper = messageStatusMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleNotificationMessage(Message amqpMessage, Channel channel) {
        Map<String, String> previousMdc = bindMessageContext(amqpMessage);
        String msgId = getMessageId(amqpMessage);
        long deliveryTag = getDeliveryTag(amqpMessage);
        NotificationEvent event = null;

        try {
            event = deserializeNotificationEvent(amqpMessage);
            msgId = resolveEventId(event, amqpMessage);
            if (ackIfDuplicate(channel, deliveryTag, IDEMPOTENT_PREFIX, msgId, IDEMPOTENT_EXPIRE, MESSAGE_TYPE)) {
                return;
            }

            TuiNotification notification = buildNotification(event);
            notificationMapper.insert(notification);
            redisUtil.incr(UNREAD_COUNT_PREFIX + event.getUserId());

            markProcessed(IDEMPOTENT_PREFIX, msgId, IDEMPOTENT_EXPIRE);
            if (msgId != null) {
                messageStatusMapper.transitionStatus(msgId,
                        CONSUMER_MUTABLE_STATES,
                        MqMessageStatusConstant.CONSUMED,
                        null);
            }
            ack(channel, deliveryTag);
            logger.info("Notification processed, msgId: {}, notificationId: {}", msgId, notification.getId());
        } catch (Exception e) {
            logger.error("Failed to process notification, msgId: {}, payloadBytes: {}",
                    msgId, bodyLength(amqpMessage), e);
            if (msgId != null) {
                messageStatusMapper.transitionStatus(msgId,
                        CONSUMER_MUTABLE_STATES,
                        MqMessageStatusConstant.CONSUME_FAILED,
                        e.getMessage());
            }
            handleRetryOrDlq(event, channel, amqpMessage,
                    RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                    RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY,
                    MESSAGE_TYPE);
        } finally {
            restoreMessageContext(previousMdc);
        }
    }

    private NotificationEvent deserializeNotificationEvent(Message amqpMessage) throws Exception {
        JsonNode root = objectMapper.readTree(amqpMessage.getBody());
        NotificationEvent event = hasEventMetadata(root)
                ? objectMapper.treeToValue(root, NotificationEvent.class)
                : deserializeLegacyNotification(root);
        enrichBaseFields(event, amqpMessage, NotificationEvent.EVENT_TYPE, NotificationEvent.EVENT_VERSION);
        return event;
    }

    private NotificationEvent deserializeLegacyNotification(JsonNode root) {
        NotificationEvent event = new NotificationEvent();
        event.setUserId(root.path("userId").longValue());
        event.setSenderId(root.path("senderId").longValue());
        event.setNotificationType(root.path("notificationType").isMissingNode()
                ? root.path("type").intValue()
                : root.path("notificationType").intValue());
        event.setContent(root.path("content").asText(null));
        event.setTargetId(root.path("targetId").asText(null));
        return event;
    }

    private TuiNotification buildNotification(NotificationEvent event) {
        TuiNotification notification = new TuiNotification();
        notification.setUserId(event.getUserId());
        notification.setSenderId(event.getSenderId());
        notification.setType(event.getNotificationType());
        notification.setContent(event.getContent());
        notification.setTargetId(event.getTargetId());
        notification.setIsRead(0);
        notification.setCreateTime(new Date());
        return notification;
    }
}
