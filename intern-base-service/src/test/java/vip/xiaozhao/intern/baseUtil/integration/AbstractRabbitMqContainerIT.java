package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.RabbitMQContainer;

public abstract class AbstractRabbitMqContainerIT {

    protected static RabbitMQContainer rabbitMqContainer;

    @BeforeAll
    static void startRabbitMqContainer() {
        ContainerTestSupport.assumeDockerAvailable();
        rabbitMqContainer = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
                .withUser("test", "test-password")
                .withVhost("/");
        rabbitMqContainer.start();
    }

    @AfterAll
    static void stopRabbitMqContainer() {
        if (rabbitMqContainer != null) {
            rabbitMqContainer.stop();
        }
    }
}
