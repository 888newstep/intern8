package vip.xiaozhao.intern.baseUtil.integration;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote broker smoke test. It is disabled unless explicitly enabled by environment/system properties.
 */
class RabbitMqRemoteIT {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqRemoteIT.class);

    private static final String ENABLED_PROPERTY = "rabbitmq.it.enabled";
    private static final String HOST_PROPERTY = "rabbitmq.it.host";
    private static final String PORT_PROPERTY = "rabbitmq.it.port";
    private static final String USERNAME_PROPERTY = "rabbitmq.it.username";
    private static final String PASSWORD_PROPERTY = "rabbitmq.it.password";
    private static final String VHOST_PROPERTY = "rabbitmq.it.vhost";

    private static final BlockingQueue<ReturnedMessage> returnedMessages = new LinkedBlockingQueue<>();

    private static CachingConnectionFactory connectionFactory;
    private static ConnectionFactory rawConnectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitAdmin rabbitAdmin;
    private static String exchangeName;
    private static String queueName;
    private static String routingKey;
    private static String deadLetterExchangeName;
    private static String deadLetterQueueName;
    private static String deadLetterRoutingKey;
    private static String retryExchangeName;
    private static String retryQueueName;
    private static String retryRoutingKey;

    private static final int RETRY_TTL_MILLIS = 500;

    @BeforeAll
    static void initializeRemoteBroker() {
        if (!isEnabled()) {
            Assumptions.assumeTrue(false,
                    "Remote RabbitMQ IT disabled; set rabbitmq.it.enabled=true to run");
        }

        String host = requiredValue(HOST_PROPERTY, "RABBITMQ_IT_HOST");
        int port = parsePort(value(PORT_PROPERTY, "RABBITMQ_IT_PORT", "5672"));
        String username = requiredValue(USERNAME_PROPERTY, "RABBITMQ_IT_USERNAME");
        String password = requiredValue(PASSWORD_PROPERTY, "RABBITMQ_IT_PASSWORD");
        String vhost = value(VHOST_PROPERTY, "RABBITMQ_IT_VHOST", "/intern8");

        connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(username);
        connectionFactory.setPassword(password);
        connectionFactory.setVirtualHost(vhost);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        rawConnectionFactory = new ConnectionFactory();
        rawConnectionFactory.setHost(host);
        rawConnectionFactory.setPort(port);
        rawConnectionFactory.setUsername(username);
        rawConnectionFactory.setPassword(password);
        rawConnectionFactory.setVirtualHost(vhost);
        rawConnectionFactory.setAutomaticRecoveryEnabled(false);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returnedMessages::offer);
        rabbitAdmin = new RabbitAdmin(connectionFactory);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        exchangeName = "it.rabbit.exchange." + suffix;
        queueName = "it.rabbit.queue." + suffix;
        routingKey = "it.rabbit.route." + suffix;
        deadLetterExchangeName = "it.rabbit.dlx.exchange." + suffix;
        deadLetterQueueName = "it.rabbit.dlq." + suffix;
        deadLetterRoutingKey = routingKey + ".dead";
        retryExchangeName = "it.rabbit.retry.exchange." + suffix;
        retryQueueName = "it.rabbit.retry.queue." + suffix;
        retryRoutingKey = routingKey + ".retry";

        // RabbitMQ 4 rejects transient non-exclusive queues by default.
        DirectExchange exchange = new DirectExchange(exchangeName, true, true);
        DirectExchange deadLetterExchange = new DirectExchange(deadLetterExchangeName, true, true);
        DirectExchange retryExchange = new DirectExchange(retryExchangeName, true, true);
        Queue queue = new Queue(
                queueName,
                true,
                false,
                true,
                Map.of(
                        "x-dead-letter-exchange", deadLetterExchangeName,
                        "x-dead-letter-routing-key", deadLetterRoutingKey));
        Queue deadLetterQueue = new Queue(deadLetterQueueName, true, false, true);
        Queue retryQueue = new Queue(
                retryQueueName,
                true,
                false,
                true,
                Map.of(
                        "x-message-ttl", RETRY_TTL_MILLIS,
                        "x-dead-letter-exchange", exchangeName,
                        "x-dead-letter-routing-key", routingKey));
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(deadLetterRoutingKey);
        Binding retryBinding = BindingBuilder.bind(retryQueue).to(retryExchange).with(retryRoutingKey);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareExchange(deadLetterExchange);
        rabbitAdmin.declareExchange(retryExchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareQueue(deadLetterQueue);
        rabbitAdmin.declareQueue(retryQueue);
        rabbitAdmin.declareBinding(binding);
        rabbitAdmin.declareBinding(deadLetterBinding);
        rabbitAdmin.declareBinding(retryBinding);
    }

    @BeforeEach
    void purgeTemporaryQueue() {
        returnedMessages.clear();
        rabbitAdmin.purgeQueue(queueName, false);
        rabbitAdmin.purgeQueue(deadLetterQueueName, false);
        rabbitAdmin.purgeQueue(retryQueueName, false);
    }

    @AfterAll
    static void cleanupRemoteBrokerResources() {
        try {
            if (rabbitAdmin != null) {
                deleteQueue(queueName);
                deleteQueue(deadLetterQueueName);
                deleteQueue(retryQueueName);
                deleteExchange(exchangeName);
                deleteExchange(deadLetterExchangeName);
                deleteExchange(retryExchangeName);
            }
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
        }
    }

    @Test
    void publisherConfirmAndManualAckShouldWork() throws Exception {
        String messageId = "remote-confirm-" + UUID.randomUUID();
        CorrelationData correlationData = publish(
                routingKey, messageId, "confirm-body-" + messageId);

        var confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        assertTrue(confirm.isAck(), () -> "Publisher confirm rejected: " + confirm.getReason());

        Connection firstConnection = rawConnectionFactory.newConnection();
        Channel firstChannel = firstConnection.createChannel();
        GetResponse firstDelivery;
        try {
            firstDelivery = pollMessage(firstChannel, queueName, Duration.ofSeconds(10));
            assertNotNull(firstDelivery);
            assertEquals(messageId, firstDelivery.getProps().getMessageId());
            assertFalse(firstDelivery.getEnvelope().isRedeliver());
        } finally {
            firstChannel.close();
            firstConnection.close();
        }

        Connection recoveryConnection = rawConnectionFactory.newConnection();
        Channel recoveryChannel = recoveryConnection.createChannel();
        try {
            GetResponse redelivered = pollMessage(recoveryChannel, queueName, Duration.ofSeconds(10));
            assertNotNull(redelivered);
            assertEquals(messageId, redelivered.getProps().getMessageId());
            assertTrue(redelivered.getEnvelope().isRedeliver());
            recoveryChannel.basicAck(redelivered.getEnvelope().getDeliveryTag(), false);
        } finally {
            recoveryChannel.close();
            recoveryConnection.close();
        }
    }

    @Test
    void messageContextHeadersShouldSurviveBrokerRoundTrip() throws Exception {
        String messageId = "remote-context-" + UUID.randomUUID();
        String requestId = "request-" + messageId;
        String userId = "user-100";
        String clientIp = "192.0.2.10";
        CorrelationData correlationData = publishWithContext(
                routingKey, messageId, "context-body-" + messageId, requestId, userId, clientIp);

        var confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        assertTrue(confirm.isAck(), () -> "Context header publish rejected: " + confirm.getReason());

        Connection connection = rawConnectionFactory.newConnection();
        Channel channel = connection.createChannel();
        try {
            GetResponse delivery = pollMessage(channel, queueName, Duration.ofSeconds(10));
            assertNotNull(delivery);
            assertEquals(messageId, delivery.getProps().getMessageId());
            assertTrue(headerValueEquals(requestId, delivery.getProps().getHeaders().get("requestId")));
            assertTrue(headerValueEquals(userId, delivery.getProps().getHeaders().get("userId")));
            assertTrue(headerValueEquals(clientIp, delivery.getProps().getHeaders().get("clientIp")));
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        } finally {
            channel.close();
            connection.close();
        }
    }

    @Test
    void mandatoryReturnShouldExposeUnroutableMessage() throws Exception {
        String messageId = "remote-return-" + UUID.randomUUID();
        String unroutableKey = routingKey + ".missing";
        CorrelationData correlationData = publish(
                unroutableKey, messageId, "return-body-" + messageId);

        var confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        assertTrue(confirm.isAck(), () -> "Exchange rejected publish: " + confirm.getReason());

        ReturnedMessage returned = returnedMessages.poll(10, TimeUnit.SECONDS);
        assertNotNull(returned);
        assertEquals(messageId, returned.getMessage().getMessageProperties().getMessageId());
        assertEquals(unroutableKey, returned.getRoutingKey());
    }

    @Test
    void basicNackShouldRouteMessageToDeadLetterQueue() throws Exception {
        String messageId = "remote-nack-" + UUID.randomUUID();
        String body = "nack-body-" + messageId;
        CorrelationData correlationData = publish(routingKey, messageId, body);

        var confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        assertTrue(confirm.isAck(), () -> "Exchange rejected publish: " + confirm.getReason());

        Connection connection = rawConnectionFactory.newConnection();
        Channel channel = connection.createChannel();
        try {
            GetResponse delivery = pollMessage(channel, queueName, Duration.ofSeconds(10));
            assertNotNull(delivery);
            assertEquals(messageId, delivery.getProps().getMessageId());
            assertEquals(body, new String(delivery.getBody(), StandardCharsets.UTF_8));

            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);

            GetResponse deadLetter = pollMessage(channel, deadLetterQueueName, Duration.ofSeconds(10));
            assertNotNull(deadLetter);
            assertEquals(messageId, deadLetter.getProps().getMessageId());
            assertEquals(body, new String(deadLetter.getBody(), StandardCharsets.UTF_8));
            assertTrue(hasDeathRecord(deadLetter, "rejected", queueName),
                    () -> "Missing rejected x-death record: " + deadLetter.getProps().getHeaders());
            channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(), false);
        } finally {
            channel.close();
            connection.close();
        }
    }

    @Test
    void retryQueueTtlShouldRouteExpiredMessageBackToMainQueue() throws Exception {
        String messageId = "remote-ttl-retry-" + UUID.randomUUID();
        String body = "ttl-retry-body-" + messageId;
        CorrelationData correlationData = publish(
                retryExchangeName, retryRoutingKey, messageId, body);

        var confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        assertTrue(confirm.isAck(), () -> "Retry exchange rejected publish: " + confirm.getReason());

        Connection connection = rawConnectionFactory.newConnection();
        Channel channel = connection.createChannel();
        try {
            GetResponse delivery = pollMessage(channel, queueName, Duration.ofSeconds(10));
            assertNotNull(delivery);
            assertEquals(messageId, delivery.getProps().getMessageId());
            assertEquals(body, new String(delivery.getBody(), StandardCharsets.UTF_8));
            assertTrue(hasDeathRecord(delivery, "expired", retryQueueName),
                    () -> "Missing expired x-death record: " + delivery.getProps().getHeaders());
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        } finally {
            channel.close();
            connection.close();
        }
    }

    private static CorrelationData publish(String route, String messageId, String body) {
        return publish(exchangeName, route, messageId, body);
    }

    private static CorrelationData publishWithContext(
            String route, String messageId, String body,
            String requestId, String userId, String clientIp) {
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.convertAndSend(
                exchangeName,
                route,
                body,
                message -> {
                    message.getMessageProperties().setMessageId(messageId);
                    message.getMessageProperties().setHeader("requestId", requestId);
                    message.getMessageProperties().setHeader("userId", userId);
                    message.getMessageProperties().setHeader("clientIp", clientIp);
                    return message;
                },
                correlationData);
        return correlationData;
    }

    private static CorrelationData publish(
            String targetExchange, String route, String messageId, String body) {
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.convertAndSend(
                targetExchange,
                route,
                body,
                message -> {
                    message.getMessageProperties().setMessageId(messageId);
                    message.getMessageProperties().setHeader("requestId", messageId);
                    return message;
                },
                correlationData);
        return correlationData;
    }

    private static GetResponse pollMessage(Channel channel, String targetQueue, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            GetResponse response = channel.basicGet(targetQueue, false);
            if (response != null) {
                return response;
            }
            Thread.sleep(100L);
        }
        return null;
    }

    private static boolean hasDeathRecord(GetResponse response, String reason, String queue) {
        Object xDeath = response.getProps().getHeaders().get("x-death");
        if (!(xDeath instanceof Iterable<?> records)) {
            return false;
        }
        for (Object record : records) {
            if (record instanceof Map<?, ?> death
                    && headerValueEquals(reason, death.get("reason"))
                    && headerValueEquals(queue, death.get("queue"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean headerValueEquals(String expected, Object actual) {
        return actual != null && expected.equals(actual.toString());
    }

    private static void deleteQueue(String name) {
        if (name != null) {
            try {
                rabbitAdmin.deleteQueue(name);
            } catch (RuntimeException exception) {
                log.warn("Unable to delete temporary RabbitMQ queue {} during cleanup", name, exception);
            }
        }
    }

    private static void deleteExchange(String name) {
        if (name != null) {
            try {
                rabbitAdmin.deleteExchange(name);
            } catch (RuntimeException exception) {
                log.warn("Unable to delete temporary RabbitMQ exchange {} during cleanup", name, exception);
            }
        }
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(value(ENABLED_PROPERTY, "RABBITMQ_IT_ENABLED", "false"));
    }

    private static String requiredValue(String property, String environment) {
        String value = value(property, environment, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing remote RabbitMQ setting: -D" + property + " or " + environment);
        }
        return value;
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured.trim();
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid RabbitMQ port: " + value, exception);
        }
    }
}
