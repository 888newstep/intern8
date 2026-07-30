package vip.xiaozhao.intern.baseUtil.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;

import jakarta.annotation.PostConstruct;
import java.util.UUID;

@Service
@SuppressWarnings("all")
public class RabbitMQSender {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQSender.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQSender(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 初始化RabbitTemplate回调：Publisher Confirm + ReturnCallback
     */
    @PostConstruct
    public void init() {
        // 消息到达交换机回调（ConfirmCallback）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String msgId = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                logger.debug("Message confirmed successfully, msgId: {}", msgId);
            } else {
                logger.error("Message confirmation failed, msgId: {}, cause: {}", msgId, cause);
            }
        });

        // 消息无法路由到队列回调（ReturnCallback）
        rabbitTemplate.setReturnsCallback(returned -> {
            logger.warn("Message returned: exchange={}, routingKey={}, replyText={}, msgId={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyText(),
                    returned.getMessage().getMessageProperties().getMessageId());
        });
    }

    /**
     * 发送通知消息（带确认+消息ID）
     */
    public void sendNotificationMessage(String message) {
        String msgId = UUID.randomUUID().toString().replace("-", "");
        CorrelationData correlationData = new CorrelationData(msgId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_ROUTING_KEY,
                message,
                m -> {
                    m.getMessageProperties().setMessageId(msgId);
                    m.getMessageProperties().setHeader("x-retry-count", 0);
                    return m;
                },
                correlationData
        );
        logger.debug("Notification message sent, msgId: {}", msgId);
    }

    /**
     * 发送动态延迟归档消息（7天后自动过期归档）
     *
     * @param dynamicId 动态ID
     */
    public void sendArchiveMessage(Long dynamicId) {
        String msgId = UUID.randomUUID().toString().replace("-", "");
        CorrelationData correlationData = new CorrelationData(msgId + "_archive");
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ARCHIVE_EXCHANGE,
                RabbitMQConfig.ARCHIVE_ROUTING_KEY,
                String.valueOf(dynamicId),
                m -> {
                    m.getMessageProperties().setMessageId(msgId);
                    m.getMessageProperties().setHeader("x-retry-count", 0);
                    return m;
                },
                correlationData
        );
        logger.info("Archive message sent for dynamicId: {}, msgId: {}", dynamicId, msgId);
    }

    /**
     * 发送到重试队列（延迟重试）
     */
    public void sendToRetryQueue(String exchange, String routingKey, String message, String msgId, int retryCount) {
        CorrelationData correlationData = new CorrelationData(msgId + "_retry_" + retryCount);
        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                message,
                m -> {
                    m.getMessageProperties().setMessageId(msgId);
                    m.getMessageProperties().setHeader("x-retry-count", retryCount);
                    return m;
                },
                correlationData
        );
        logger.info("Message sent to retry queue, msgId: {}, retryCount: {}, retryQueue: {}",
                msgId, retryCount, routingKey);
    }
}