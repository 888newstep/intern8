package vip.xiaozhao.intern.baseUtil.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiNotification;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiNotificationMapper;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.lang.reflect.Type;
import java.util.Date;
import java.util.Map;

@Service
public class RabbitMQHandler {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQHandler.class);
    private static final Gson gson = new Gson();

    // Redis幂等性前缀
    private static final String IDEMPOTENT_PREFIX = "mq:msg:id:";
    // 幂等性记录过期时间（24小时）
    private static final long IDEMPOTENT_EXPIRE = 86400;

    private final TuiDynamicMapper dynamicMapper;
    private final TuiNotificationMapper notificationMapper;
    private final RedisUtil redisUtil;
    private final RabbitMQSender rabbitMQSender;

    public RabbitMQHandler(TuiDynamicMapper dynamicMapper, TuiNotificationMapper notificationMapper,
                           RedisUtil redisUtil, RabbitMQSender rabbitMQSender) {
        this.dynamicMapper = dynamicMapper;
        this.notificationMapper = notificationMapper;
        this.redisUtil = redisUtil;
        this.rabbitMQSender = rabbitMQSender;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleNotificationMessage(String message, Channel channel, Message amqpMessage) {
        String msgId = amqpMessage.getMessageProperties().getMessageId();
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        try {
            // 1. 幂等性检查
            if (isDuplicate(msgId)) {
                logger.warn("Duplicate notification message ignored, msgId: {}", msgId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 解析消息
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> notificationInfo = gson.fromJson(message, type);

            // 3. 持久化通知
            TuiNotification notification = new TuiNotification();
            notification.setUserId(((Number) notificationInfo.get("userId")).longValue());
            notification.setSenderId(((Number) notificationInfo.get("senderId")).longValue());
            notification.setType(((Number) notificationInfo.get("type")).intValue());
            notification.setContent((String) notificationInfo.get("content"));
            notification.setTargetId((String) notificationInfo.get("targetId"));
            notification.setIsRead(0);
            notification.setCreateTime(new Date());

            notificationMapper.insert(notification);

            // 4. 记录幂等性
            markProcessed(msgId);

            // 5. 手动ACK确认
            channel.basicAck(deliveryTag, false);
            logger.info("Notification message processed successfully, msgId: {}, notificationId: {}", msgId, notification.getId());

        } catch (Exception e) {
            logger.error("Failed to process notification message, msgId: {}, message: {}", msgId, message, e);
            handleRetryOrDlq(message, channel, amqpMessage, RabbitMQConfig.NOTIFICATION_DLX_EXCHANGE,
                    RabbitMQConfig.NOTIFICATION_RETRY_ROUTING_KEY, "notification");
        }
    }

    /**
     * 处理延迟归档消息：动态发布7天后自动归档
     * 归档逻辑：设置status=2（已归档），释放关联资源
     */
    @RabbitListener(queues = RabbitMQConfig.ARCHIVE_QUEUE)
    public void handleArchiveMessage(String message, Channel channel, Message amqpMessage) {
        String msgId = amqpMessage.getMessageProperties().getMessageId();
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        try {
            Long dynamicId = Long.parseLong(message);
            logger.info("Archiving dynamic: dynamicId={}, msgId={}", dynamicId, msgId);

            // 执行归档：设置status=2（已归档）
            dynamicMapper.archiveById(dynamicId);

            channel.basicAck(deliveryTag, false);
            logger.info("Dynamic archived successfully, dynamicId: {}, msgId: {}", dynamicId, msgId);
        } catch (Exception e) {
            logger.error("Failed to archive dynamic, msgId: {}, message: {}", msgId, message, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                logger.error("Failed to nack archive message, msgId: {}", msgId, ex);
            }
        }
    }

    // ==================== 幂等性处理 ====================

    /**
     * 检查消息是否已被消费过（Redis SETNX）
     */
    private boolean isDuplicate(String msgId) {
        if (msgId == null) {
            return false;
        }
        String key = IDEMPOTENT_PREFIX + msgId;
        Long result = redisUtil.setnx(key, "1");
        if (result != null && result == 1) {
            // 首次消费，设置过期时间
            redisUtil.expire(key, IDEMPOTENT_EXPIRE);
            return false;
        }
        // 已存在，说明是重复消息
        return true;
    }

    /**
     * 记录已处理的消息ID（兼容setnx失败的场景）
     */
    private void markProcessed(String msgId) {
        if (msgId == null) {
            return;
        }
        String key = IDEMPOTENT_PREFIX + msgId;
        // 确保幂等Key已设置
        redisUtil.set(key, "1", (int) IDEMPOTENT_EXPIRE);
    }

    // ==================== 重试/死信处理 ====================

    /**
     * 处理消费失败的消息：重试次数未达上限则进入重试队列，否则进入死信队列
     */
    private void handleRetryOrDlq(String message, Channel channel, Message amqpMessage,
                                   String dlxExchange, String retryRoutingKey, String msgType) {
        String msgId = amqpMessage.getMessageProperties().getMessageId();
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        // 获取当前重试次数
        Integer retryCount = 0;
        Object retryHeader = amqpMessage.getMessageProperties().getHeader("x-retry-count");
        if (retryHeader instanceof Number) {
            retryCount = ((Number) retryHeader).intValue();
        }

        try {
            if (retryCount < RabbitMQConfig.MAX_RETRY_COUNT) {
                // 未达上限：进入重试队列（TTL到期后自动回到主队列）
                int nextRetryCount = retryCount + 1;
                rabbitMQSender.sendToRetryQueue(dlxExchange, retryRoutingKey, message, msgId, nextRetryCount);
                channel.basicAck(deliveryTag, false);
                logger.warn("{} message sent to retry queue, msgId: {}, retryCount: {}/{}",
                        msgType, msgId, nextRetryCount, RabbitMQConfig.MAX_RETRY_COUNT);
            } else {
                // 已达上限：进入死信队列（由主队列的DLX自动路由）
                channel.basicNack(deliveryTag, false, false);
                logger.error("{} message exceeded max retries, sent to DLQ, msgId: {}, retryCount: {}",
                        msgType, msgId, retryCount);
            }
        } catch (Exception e) {
            logger.error("Failed to handle retry/DLQ for {} message, msgId: {}", msgType, msgId, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                logger.error("Failed to nack message, msgId: {}", msgId, ex);
            }
        }
    }
}