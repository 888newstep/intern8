package vip.xiaozhao.intern.baseUtil.integration;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Container-backed verification of the production Outbox schema and mapper. */
class MqOutboxMySqlIT extends AbstractMySqlContainerIT {

    private static final String BUSINESS_TABLE = "outbox_it_business";
    private static final int MAX_RETRY_COUNT = 3;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initializeSchemaAndMapper() throws Exception {
        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        configuredDataSource.setUrl(mysqlContainer.getJdbcUrl());
        configuredDataSource.setUsername(mysqlContainer.getUsername());
        configuredDataSource.setPassword(mysqlContainer.getPassword());
        dataSource = configuredDataSource;

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/mq_outbox.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS " + BUSINESS_TABLE + " ("
                                + "id BIGINT PRIMARY KEY,"
                                + "event_id VARCHAR(64) NOT NULL UNIQUE,"
                                + "payload VARCHAR(255) NOT NULL"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        }

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 该测试不启动 Spring 容器，显式使用 JDBC 事务工厂验证 commit/rollback 语义。
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/MqOutboxMapper.xml"));
        factoryBean.afterPropertiesSet();
        sqlSessionFactory = Objects.requireNonNull(
                factoryBean.getObject(), "MyBatis SqlSessionFactory must be initialized");
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + BUSINESS_TABLE);
            statement.executeUpdate("DELETE FROM mq_outbox");
        }
    }

    @Test
    void businessWriteAndOutboxWriteShouldCommitAtomically() throws Exception {
        String eventId = eventId("commit");

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            insertBusinessRecord(session.getConnection(), 1001L, eventId);
            assertEquals(1, session.getMapper(MqOutboxMapper.class).insert(outbox(eventId)));
            session.commit();
        }

        assertEquals(1, countBusinessRecords(eventId));
        assertNotNull(findOutbox(eventId));
    }

    @Test
    void businessWriteAndOutboxWriteShouldRollbackTogether() throws Exception {
        String eventId = eventId("rollback");

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            insertBusinessRecord(session.getConnection(), 1002L, eventId);
            assertEquals(1, session.getMapper(MqOutboxMapper.class).insert(outbox(eventId)));
            session.rollback();
        }

        assertEquals(0, countBusinessRecords(eventId));
        assertNull(findOutbox(eventId));
    }

    @Test
    void onlyOneWorkerShouldClaimTheSameOutboxRow() throws Exception {
        String eventId = eventId("claim");
        persistOutbox(eventId);
        Long outboxId = requiredOutboxId(eventId);

        // 候选读取与条件 claim 使用独立事务，模拟 relay 每轮短事务的边界。
        try (SqlSession firstObserver = sqlSessionFactory.openSession(true);
             SqlSession secondObserver = sqlSessionFactory.openSession(true)) {
            assertEquals(1, firstObserver.getMapper(MqOutboxMapper.class)
                    .selectDispatchable(10, MAX_RETRY_COUNT).size());
            assertEquals(1, secondObserver.getMapper(MqOutboxMapper.class)
                    .selectDispatchable(10, MAX_RETRY_COUNT).size());
        }

        try (SqlSession firstWorker = sqlSessionFactory.openSession(false)) {
            assertEquals(1, firstWorker.getMapper(MqOutboxMapper.class).claim(
                    outboxId, dateAfterSeconds(60), MAX_RETRY_COUNT));
            firstWorker.commit();
        }

        try (SqlSession secondWorker = sqlSessionFactory.openSession(false)) {
            assertEquals(0, secondWorker.getMapper(MqOutboxMapper.class).claim(
                    outboxId, dateAfterSeconds(120), MAX_RETRY_COUNT));
            secondWorker.commit();
        }

        MqOutbox claimed = findOutbox(eventId);
        assertNotNull(claimed);
        assertEquals(1, claimed.getStatus());
        assertNotNull(claimed.getLeaseUntil());
    }

    @Test
    void expiredLeaseShouldBecomeClaimableByAnotherWorker() throws Exception {
        String eventId = eventId("lease-recovery");
        persistOutbox(eventId);
        Long outboxId = requiredOutboxId(eventId);

        try (SqlSession firstWorker = sqlSessionFactory.openSession(false)) {
            assertEquals(1, firstWorker.getMapper(MqOutboxMapper.class)
                    .claim(outboxId, dateAfterSeconds(60), MAX_RETRY_COUNT));
            firstWorker.commit();
        }

        expireLease(outboxId);

        try (SqlSession recoveryWorker = sqlSessionFactory.openSession(false)) {
            MqOutboxMapper mapper = recoveryWorker.getMapper(MqOutboxMapper.class);
            List<MqOutbox> dispatchable = mapper.selectDispatchable(10, MAX_RETRY_COUNT);

            assertTrue(dispatchable.stream().anyMatch(item -> outboxId.equals(item.getId())));
            assertEquals(1, mapper.claim(
                    outboxId, dateAfterSeconds(120), MAX_RETRY_COUNT));
            recoveryWorker.commit();
        }

        MqOutbox recovered = findOutbox(eventId);
        assertNotNull(recovered);
        assertEquals(1, recovered.getStatus());
        assertNotNull(recovered.getLeaseUntil());
    }

    private static void persistOutbox(String eventId) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            assertEquals(1, session.getMapper(MqOutboxMapper.class).insert(outbox(eventId)));
            session.commit();
        }
    }

    private static Long requiredOutboxId(String eventId) throws Exception {
        MqOutbox persisted = findOutbox(eventId);
        assertNotNull(persisted);
        assertNotNull(persisted.getId());
        return persisted.getId();
    }

    private static MqOutbox findOutbox(String eventId) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(MqOutboxMapper.class).selectByEventId(eventId);
        }
    }

    private static void insertBusinessRecord(Connection connection, long id, String eventId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + BUSINESS_TABLE + " (id, event_id, payload) VALUES (?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, eventId);
            statement.setString(3, "transactional-outbox-it");
            statement.executeUpdate();
        }
    }

    private static long countBusinessRecords(String eventId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + BUSINESS_TABLE + " WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static void expireLease(Long outboxId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE mq_outbox SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND)"
                             + " WHERE id = ?")) {
            statement.setLong(1, outboxId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static MqOutbox outbox(String eventId) {
        MqOutbox outbox = new MqOutbox();
        outbox.setEventId(eventId);
        outbox.setEventType("integration.test");
        outbox.setExchangeName("integration.exchange");
        outbox.setRoutingKey("integration.test");
        outbox.setMessageBody("payload-" + eventId);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextAttemptTime(Date.from(Instant.now().minusSeconds(1)));
        outbox.setCreateTime(new Date());
        outbox.setUpdateTime(new Date());
        return outbox;
    }

    private static String eventId(String suffix) {
        String eventId = "outbox-it-" + suffix + "-"
                + UUID.randomUUID().toString().replace("-", "");
        if (eventId.length() > 64) {
            throw new IllegalStateException("Generated event ID exceeds mq_outbox.event_id limit");
        }
        return eventId;
    }

    private static Date dateAfterSeconds(long seconds) {
        return Date.from(Instant.now().plusSeconds(seconds));
    }
}
