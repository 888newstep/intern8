package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // ==================== 通知队列 ====================
    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.routing.key";

    // 通知死信
    public static final String NOTIFICATION_DLX_EXCHANGE = "notification.dlx.exchange";
    public static final String NOTIFICATION_DLQ_QUEUE = "notification.dlq.queue";
    public static final String NOTIFICATION_DLX_ROUTING_KEY = "notification.dlx.routing.key";

    // 通知重试
    public static final String NOTIFICATION_RETRY_QUEUE = "notification.retry.queue";
    public static final String NOTIFICATION_RETRY_ROUTING_KEY = "notification.retry.routing.key";
    public static final long NOTIFICATION_RETRY_TTL = 10000; // 10秒后重试

    // ==================== 延迟归档队列（动态7天后自动过期归档） ====================
    public static final String ARCHIVE_EXCHANGE = "archive.exchange";
    public static final String ARCHIVE_QUEUE = "archive.queue";
    public static final String ARCHIVE_ROUTING_KEY = "archive.dynamic";
    // 7天延迟（毫秒）
    public static final long ARCHIVE_DELAY_TTL = 7 * 24 * 60 * 60 * 1000L;

    // 最大重试次数
    public static final int MAX_RETRY_COUNT = 3;

    // ==================== 交换机声明 ====================

    @Bean
    public DirectExchange notificationExchange() {
        return ExchangeBuilder.directExchange(NOTIFICATION_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return ExchangeBuilder.directExchange(NOTIFICATION_DLX_EXCHANGE).durable(true).build();
    }

    // ==================== 队列声明 ====================

    @Bean
    public Queue notificationQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", NOTIFICATION_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", NOTIFICATION_DLX_ROUTING_KEY);
        return QueueBuilder.durable(NOTIFICATION_QUEUE).withArguments(args).build();
    }

    @Bean
    public Queue notificationDlqQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLQ_QUEUE).build();
    }

    @Bean
    public Queue notificationRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", NOTIFICATION_RETRY_TTL);
        args.put("x-dead-letter-exchange", NOTIFICATION_EXCHANGE);
        args.put("x-dead-letter-routing-key", NOTIFICATION_ROUTING_KEY);
        return QueueBuilder.durable(NOTIFICATION_RETRY_QUEUE).withArguments(args).build();
    }

    // ==================== 归档交换机/队列（延迟7天 + 消费队列） ====================

    @Bean
    public DirectExchange archiveExchange() {
        return ExchangeBuilder.directExchange(ARCHIVE_EXCHANGE).durable(true).build();
    }

    /**
     * 延迟归档队列：消息7天后过期，自动投递到archive.exchange
     * 原理：TTL + DLX，无需额外插件
     */
    @Bean
    public Queue archiveDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ARCHIVE_DELAY_TTL);
        args.put("x-dead-letter-exchange", ARCHIVE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ARCHIVE_ROUTING_KEY);
        return QueueBuilder.durable("archive.delay.queue").withArguments(args).build();
    }

    @Bean
    public Queue archiveQueue() {
        return QueueBuilder.durable(ARCHIVE_QUEUE).build();
    }

    // ==================== 绑定声明 ====================

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(NOTIFICATION_ROUTING_KEY);
    }

    @Bean
    public Binding notificationDlxBinding(Queue notificationDlqQueue, DirectExchange notificationDlxExchange) {
        return BindingBuilder.bind(notificationDlqQueue).to(notificationDlxExchange).with(NOTIFICATION_DLX_ROUTING_KEY);
    }

    @Bean
    public Binding notificationRetryBinding(Queue notificationRetryQueue, DirectExchange notificationDlxExchange) {
        return BindingBuilder.bind(notificationRetryQueue).to(notificationDlxExchange).with(NOTIFICATION_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding archiveDelayBinding(Queue archiveDelayQueue, DirectExchange archiveExchange) {
        return BindingBuilder.bind(archiveDelayQueue).to(archiveExchange).with(ARCHIVE_ROUTING_KEY);
    }

    @Bean
    public Binding archiveBinding(Queue archiveQueue, DirectExchange archiveExchange) {
        return BindingBuilder.bind(archiveQueue).to(archiveExchange).with(ARCHIVE_ROUTING_KEY);
    }
}