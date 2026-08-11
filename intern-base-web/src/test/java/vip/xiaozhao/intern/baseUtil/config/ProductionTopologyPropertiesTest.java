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
        Properties properties = loadProperties();

        assertEquals(
                "jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:xiao}?characterEncoding=UTF8&serverTimezone=GMT%2B8&useSSL=false",
                properties.getProperty("spring.datasource.url"));
        assertEquals("${REDIS_MODE:single}", properties.getProperty("redis.mode"));
        assertEquals("${REDIS_HOST:127.0.0.1}", properties.getProperty("redis.ip"));
        assertEquals("${REDIS_PORT:6379}", properties.getProperty("redis.port"));
        assertEquals("${RABBITMQ_HOST}", properties.getProperty("spring.rabbitmq.host"));
        assertEquals("${RABBITMQ_PORT:5672}", properties.getProperty("spring.rabbitmq.port"));
        assertNull(properties.getProperty("spring.rabbitmq.addresses"));
        assertNull(properties.getProperty("spring.profiles.active"));
    }

    @Test
    void optionalRedisTopologiesRemainEnvironmentDriven() throws IOException {
        Properties properties = loadProperties();

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

    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("application-prod.properties")) {
            assertNotNull(input, "application-prod.properties must be on the test classpath");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
