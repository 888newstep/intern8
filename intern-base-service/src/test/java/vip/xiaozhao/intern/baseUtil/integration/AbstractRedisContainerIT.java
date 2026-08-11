package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;

public abstract class AbstractRedisContainerIT {

    protected static GenericContainer<?> redisContainer;

    @BeforeAll
    static void startRedisContainer() {
        ContainerTestSupport.assumeDockerAvailable();
        redisContainer = new GenericContainer<>("redis:7.2-alpine")
                .withExposedPorts(6379)
                .withCommand("redis-server", "--save", "", "--appendonly", "no");
        redisContainer.start();
    }

    @AfterAll
    static void stopRedisContainer() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    protected static String redisAddress() {
        return "redis://" + redisContainer.getHost() + ":"
                + redisContainer.getMappedPort(6379);
    }
}
