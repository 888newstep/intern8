package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;

public abstract class AbstractMySqlContainerIT {

    protected static MySQLContainer<?> mysqlContainer;

    @BeforeAll
    static void startMySqlContainer() {
        ContainerTestSupport.assumeDockerAvailable();
        mysqlContainer = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("xiao")
                .withUsername("test")
                .withPassword("test-password");
        mysqlContainer.start();
    }

    @AfterAll
    static void stopMySqlContainer() {
        if (mysqlContainer != null) {
            mysqlContainer.stop();
        }
    }
}
