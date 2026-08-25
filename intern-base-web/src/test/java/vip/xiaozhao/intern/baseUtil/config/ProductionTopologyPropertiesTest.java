package vip.xiaozhao.intern.baseUtil.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductionTopologyPropertiesTest {

    @Test
    void defaultsMatchWin11MySqlRedisAndCloudRabbitMqTopology() throws IOException {
        Properties properties = loadProperties("application-prod.properties");

        assertEquals(
                "jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:xiao}?characterEncoding=UTF8&serverTimezone=GMT%2B8&useSSL=false",
                properties.getProperty("spring.datasource.url"));
        assertEquals("${REDIS_MODE:single}", properties.getProperty("redis.mode"));
        assertEquals("${REDIS_HOST:127.0.0.1}", properties.getProperty("redis.ip"));
        assertEquals("${REDIS_PORT:6379}", properties.getProperty("redis.port"));
        assertEquals("${RABBITMQ_HOST}", properties.getProperty("spring.rabbitmq.host"));
        assertEquals("${RABBITMQ_PORT:5672}", properties.getProperty("spring.rabbitmq.port"));
        assertEquals("${RABBITMQ_VHOST:/intern8}",
                properties.getProperty("spring.rabbitmq.virtual-host"));
        assertNull(properties.getProperty("spring.rabbitmq.addresses"));
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
        assertEquals("classpath:db/migration", properties.getProperty("spring.flyway.locations"));
        assertEquals("${FLYWAY_BASELINE_ON_MIGRATE:false}",
                properties.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("true", properties.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("${MQ_COMPENSATION_MAX_RUN_SECONDS:45}",
                properties.getProperty("mq.compensation.max-run-seconds"));
        assertEquals("${MQ_COMPENSATION_PER_MESSAGE_TIMEOUT_MS:5000}",
                properties.getProperty("mq.compensation.per-message-timeout-ms"));
        assertEquals("${MQ_COMPENSATION_WORKER_THREADS:4}",
                properties.getProperty("mq.compensation.worker-threads"));
        assertEquals("${MQ_OUTBOX_PUBLISHER_CONFIRM_TIMEOUT_MS:5000}",
                properties.getProperty("mq.outbox.publisher-confirm-timeout-ms"));
        assertEquals("${MQ_OUTBOX_WORKER_THREADS:4}",
                properties.getProperty("mq.outbox.worker-threads"));
        assertNull(properties.getProperty("spring.profiles.active"));
    }

    @Test
    void optionalRedisTopologiesRemainEnvironmentDriven() throws IOException {
        Properties properties = loadProperties("application-prod.properties");

        assertEquals("${REDIS_SENTINEL_MASTER:mymaster}",
                properties.getProperty("redis.sentinel.master"));
        assertEquals("${REDIS_SENTINEL_NODES:127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381}",
                properties.getProperty("redis.sentinel.nodes"));
        assertEquals("${REDIS_CLUSTER_NODES:127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003}",
                properties.getProperty("redis.cluster.nodes"));
        assertEquals("${MYSQL_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${MYSQL_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertEquals("${RABBITMQ_USERNAME}", properties.getProperty("spring.rabbitmq.username"));
        assertEquals("${RABBITMQ_PASSWORD}", properties.getProperty("spring.rabbitmq.password"));
    }

    @Test
    void runtimeConcurrencyDefaultsAreBoundedAndEnvironmentDriven() throws IOException {
        Properties properties = loadProperties("application.properties");

        assertEquals("${TOMCAT_MAX_THREADS:200}",
                properties.getProperty("server.tomcat.threads.max"));
        assertEquals("${TOMCAT_MIN_SPARE_THREADS:20}",
                properties.getProperty("server.tomcat.threads.min-spare"));
        assertEquals("${TOMCAT_ACCEPT_COUNT:200}",
                properties.getProperty("server.tomcat.accept-count"));
        assertEquals("${DB_POOL_MAX_ACTIVE:64}",
                properties.getProperty("spring.datasource.druid.max-active"));
        assertEquals("${DB_POOL_MAX_WAIT_MS:3000}",
                properties.getProperty("spring.datasource.druid.max-wait"));
    }

    private Properties loadProperties(String resourceName) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, resourceName + " must be on the test classpath");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
