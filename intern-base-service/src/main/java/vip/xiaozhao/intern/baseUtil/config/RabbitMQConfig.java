package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.created";
    public static final String NOTIFICATION_DLX_EXCHANGE = "notification.dlx.exchange";
    public static final String NOTIFICATION_DLQ_QUEUE = "notification.dlq.queue";
    public static final String NOTIFICATION_DLX_ROUTING_KEY = "notification.dlx.routing.key";
    public static final String NOTIFICATION_RETRY_QUEUE = "notification.retry.queue";
    public static final String NOTIFICATION_RETRY_ROUTING_KEY = "notification.retry.routing.key";
    public static final long NOTIFICATION_RETRY_TTL = 10000L;

    public static final String ARCHIVE_DELAY_EXCHANGE = "archive.delay.exchange";
    public static final String ARCHIVE_DELAY_QUEUE = "archive.delay.queue";
    public static final String ARCHIVE_DELAY_ROUTING_KEY = "archive.delay.publish";
    public static final String ARCHIVE_EXCHANGE = "archive.exchange";
    public static final String ARCHIVE_QUEUE = "archive.queue";
    public static final String ARCHIVE_ROUTING_KEY = "archive.dynamic.execute";
    public static final String ARCHIVE_DLX_EXCHANGE = "archive.dlx.exchange";
    public static final String ARCHIVE_DLQ_QUEUE = "archive.dlq.queue";
    public static final String ARCHIVE_DLX_ROUTING_KEY = "archive.dlx.routing.key";
    public static final String ARCHIVE_RETRY_QUEUE = "archive.retry.queue";
    public static final String ARCHIVE_RETRY_ROUTING_KEY = "archive.retry.routing.key";
    public static final long ARCHIVE_DELAY_TTL = 7 * 24 * 60 * 60 * 1000L;
    public static final long ARCHIVE_RETRY_TTL = 60000L;

    public static final int MAX_RETRY_COUNT = 3;

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange notificationExchange() {
        return ExchangeBuilder.directExchange(NOTIFICATION_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return ExchangeBuilder.directExchange(NOTIFICATION_DLX_EXCHANGE).durable(true).build();
    }

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

    @Bean
    public DirectExchange archiveDelayExchange() {
        return ExchangeBuilder.directExchange(ARCHIVE_DELAY_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange archiveExchange() {
        return ExchangeBuilder.directExchange(ARCHIVE_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange archiveDlxExchange() {
        return ExchangeBuilder.directExchange(ARCHIVE_DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue archiveDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ARCHIVE_DELAY_TTL);
        args.put("x-dead-letter-exchange", ARCHIVE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ARCHIVE_ROUTING_KEY);
        return QueueBuilder.durable(ARCHIVE_DELAY_QUEUE).withArguments(args).build();
    }

    @Bean
    public Queue archiveQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ARCHIVE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", ARCHIVE_DLX_ROUTING_KEY);
        return QueueBuilder.durable(ARCHIVE_QUEUE).withArguments(args).build();
    }

    @Bean
    public Queue archiveDlqQueue() {
        return QueueBuilder.durable(ARCHIVE_DLQ_QUEUE).build();
    }

    @Bean
    public Queue archiveRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ARCHIVE_RETRY_TTL);
        args.put("x-dead-letter-exchange", ARCHIVE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ARCHIVE_ROUTING_KEY);
        return QueueBuilder.durable(ARCHIVE_RETRY_QUEUE).withArguments(args).build();
    }

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
    public Binding archiveDelayBinding(Queue archiveDelayQueue, DirectExchange archiveDelayExchange) {
        return BindingBuilder.bind(archiveDelayQueue).to(archiveDelayExchange).with(ARCHIVE_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding archiveBinding(Queue archiveQueue, DirectExchange archiveExchange) {
        return BindingBuilder.bind(archiveQueue).to(archiveExchange).with(ARCHIVE_ROUTING_KEY);
    }

    @Bean
    public Binding archiveDlxBinding(Queue archiveDlqQueue, DirectExchange archiveDlxExchange) {
        return BindingBuilder.bind(archiveDlqQueue).to(archiveDlxExchange).with(ARCHIVE_DLX_ROUTING_KEY);
    }

    @Bean
    public Binding archiveRetryBinding(Queue archiveRetryQueue, DirectExchange archiveDlxExchange) {
        return BindingBuilder.bind(archiveRetryQueue).to(archiveDlxExchange).with(ARCHIVE_RETRY_ROUTING_KEY);
    }
}
