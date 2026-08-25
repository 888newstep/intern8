package vip.xiaozhao.intern.baseUtil.integration;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqMessageStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqMessageStatusMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/**
 * 本机 MySQL 的真实 Outbox 集成测试。
 *
 * <p>测试默认关闭，避免普通构建依赖开发机状态。开启后使用独立随机 schema，
 * 不会读取、清空或删除项目已有数据库。</p>
 */
class MqOutboxMySqlLocalIT {

    private static final Logger log = LoggerFactory.getLogger(MqOutboxMySqlLocalIT.class);

    private static final String ENABLED_PROPERTY = "mysql.local.it.enabled";
    private static final String HOST_PROPERTY = "mysql.local.it.host";
    private static final String PORT_PROPERTY = "mysql.local.it.port";
    private static final String USERNAME_PROPERTY = "mysql.local.it.username";
    private static final String PASSWORD_PROPERTY = "mysql.local.it.password";
    private static final String SCHEMA_PREFIX_PROPERTY = "mysql.local.it.schema-prefix";

    private static final String HOST_ENV = "MYSQL_HOST";
    private static final String PORT_ENV = "MYSQL_PORT";
    private static final String USERNAME_ENV = "MYSQL_LOCAL_IT_USERNAME";
    private static final String PASSWORD_ENV = "MYSQL_LOCAL_IT_PASSWORD";
    private static final String FALLBACK_USERNAME_ENV = "MYSQL_USERNAME";
    private static final String FALLBACK_PASSWORD_ENV = "MYSQL_PASSWORD";
    private static final String SCHEMA_PREFIX_ENV = "MYSQL_LOCAL_IT_SCHEMA_PREFIX";

    private static final String BUSINESS_TABLE = "outbox_it_business";
    private static final int MAX_RETRY_COUNT = 3;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static String schemaName;
    private static String adminHost;
    private static int adminPort;
    private static String adminUsername;
    private static String adminPassword;

    @BeforeAll
    static void initializeSchemaAndMapper() throws Exception {
        Assumptions.assumeTrue(isEnabled(),
                "本机 MySQL IT 默认关闭，请显式设置 -D" + ENABLED_PROPERTY + "=true");

        adminHost = value(HOST_PROPERTY, HOST_ENV, "127.0.0.1");
        adminPort = parsePort(value(PORT_PROPERTY, PORT_ENV, "3306"));
        adminUsername = requiredValue(USERNAME_PROPERTY, USERNAME_ENV, FALLBACK_USERNAME_ENV);
        adminPassword = requiredValue(PASSWORD_PROPERTY, PASSWORD_ENV, FALLBACK_PASSWORD_ENV);
        schemaName = createSchemaName(value(
                SCHEMA_PREFIX_PROPERTY, SCHEMA_PREFIX_ENV, "intern_it"));

        createSchema();
        dataSource = createDataSource(schemaName);
        try {
            initializeTables();
            sqlSessionFactory = createSqlSessionFactory();
        } catch (Exception exception) {
            dropSchemaQuietly();
            throw exception;
        }
        log.info("本机 MySQL Outbox IT 已启用，使用隔离 schema {}", schemaName);
    }

    @AfterAll
    static void dropTestSchema() {
        dropSchemaQuietly();
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + BUSINESS_TABLE);
            statement.executeUpdate("DELETE FROM mq_outbox");
            statement.executeUpdate("DELETE FROM mq_message_status");
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
    void conditionalClaimShouldAllowOnlyTheFirstCommittedWorker() throws Exception {
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
            assertEquals(1, mapper.claim(outboxId, dateAfterSeconds(120), MAX_RETRY_COUNT));
            recoveryWorker.commit();
        }

        MqOutbox recovered = findOutbox(eventId);
        assertNotNull(recovered);
        assertEquals(1, recovered.getStatus());
        assertNotNull(recovered.getLeaseUntil());
    }

    @Test
    void stalePendingMessageShouldBecomeClaimableForCompensation() throws Exception {
        String messageId = messageId("stale-pending");
        persistMessageStatus(messageId, MqMessageStatusConstant.PENDING);

        try (SqlSession freshObserver = sqlSessionFactory.openSession(true)) {
            MqMessageStatusMapper mapper = freshObserver.getMapper(MqMessageStatusMapper.class);
            assertTrue(mapper.selectCompensableMessages(
                            MAX_RETRY_COUNT,
                            MqMessageStatusConstant.COMPENSATING_STALE_SECONDS,
                            10).stream()
                    .noneMatch(item -> messageId.equals(item.getMessageId())));
            assertEquals(0, mapper.claimForCompensation(
                    messageId,
                    MAX_RETRY_COUNT,
                    MqMessageStatusConstant.COMPENSATING_STALE_SECONDS));
        }

        backdateMessageStatus(messageId);

        try (SqlSession recoveryWorker = sqlSessionFactory.openSession(false)) {
            MqMessageStatusMapper mapper = recoveryWorker.getMapper(MqMessageStatusMapper.class);
            assertTrue(mapper.selectCompensableMessages(
                            MAX_RETRY_COUNT,
                            MqMessageStatusConstant.COMPENSATING_STALE_SECONDS,
                            10).stream()
                    .anyMatch(item -> messageId.equals(item.getMessageId())));
            assertEquals(1, mapper.claimForCompensation(
                    messageId,
                    MAX_RETRY_COUNT,
                    MqMessageStatusConstant.COMPENSATING_STALE_SECONDS));
            recoveryWorker.commit();
        }

        MqMessageStatus recovered = findMessageStatus(messageId);
        assertNotNull(recovered);
        assertEquals(MqMessageStatusConstant.COMPENSATING, recovered.getStatus());
    }

    @Test
    void consumedMessageShouldRejectStalePublishFailureTransition() throws Exception {
        String messageId = messageId("consumed");
        persistMessageStatus(messageId, MqMessageStatusConstant.CONFIRMED);

        try (SqlSession consumer = sqlSessionFactory.openSession(false)) {
            MqMessageStatusMapper mapper = consumer.getMapper(MqMessageStatusMapper.class);
            assertEquals(1, mapper.transitionStatus(
                    messageId,
                    List.of(MqMessageStatusConstant.CONFIRMED),
                    MqMessageStatusConstant.CONSUMED,
                    null));
            consumer.commit();
        }

        try (SqlSession stalePublisherCallback = sqlSessionFactory.openSession(false)) {
            MqMessageStatusMapper mapper = stalePublisherCallback.getMapper(MqMessageStatusMapper.class);
            assertEquals(0, mapper.transitionStatus(
                    messageId,
                    List.of(MqMessageStatusConstant.PENDING,
                            MqMessageStatusConstant.CONFIRMED,
                            MqMessageStatusConstant.COMPENSATING),
                    MqMessageStatusConstant.FAILED,
                    "late publisher return"));
            stalePublisherCallback.commit();
        }

        MqMessageStatus persisted = findMessageStatus(messageId);
        assertNotNull(persisted);
        assertEquals(MqMessageStatusConstant.CONSUMED, persisted.getStatus());
        assertNull(persisted.getLastError());
    }

    @Test
    void deadLetteredMessageShouldRejectStaleConsumerSuccessTransition() throws Exception {
        String messageId = messageId("dead-lettered");
        persistMessageStatus(messageId, MqMessageStatusConstant.COMPENSATING);

        try (SqlSession deadLetterWorker = sqlSessionFactory.openSession(false)) {
            MqMessageStatusMapper mapper = deadLetterWorker.getMapper(MqMessageStatusMapper.class);
            assertEquals(1, mapper.transitionStatus(
                    messageId,
                    List.of(MqMessageStatusConstant.COMPENSATING),
                    MqMessageStatusConstant.DEAD_LETTERED,
                    "invalid payload"));
            deadLetterWorker.commit();
        }

        try (SqlSession staleConsumerCallback = sqlSessionFactory.openSession(false)) {
            MqMessageStatusMapper mapper = staleConsumerCallback.getMapper(MqMessageStatusMapper.class);
            assertEquals(0, mapper.transitionStatus(
                    messageId,
                    List.of(MqMessageStatusConstant.PENDING,
                            MqMessageStatusConstant.CONFIRMED,
                            MqMessageStatusConstant.FAILED,
                            MqMessageStatusConstant.CONSUME_FAILED,
                            MqMessageStatusConstant.COMPENSATING),
                    MqMessageStatusConstant.CONSUMED,
                    null));
            staleConsumerCallback.commit();
        }

        MqMessageStatus persisted = findMessageStatus(messageId);
        assertNotNull(persisted);
        assertEquals(MqMessageStatusConstant.DEAD_LETTERED, persisted.getStatus());
        assertEquals("invalid payload", persisted.getLastError());
    }

    private static void createSchema() throws SQLException {
        try (Connection connection = createServerDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoteIdentifier(schemaName)
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void initializeTables() throws SQLException {
        FlywayMigrationSupport.migrate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS " + BUSINESS_TABLE + " ("
                                + "id BIGINT PRIMARY KEY,"
                                + "event_id VARCHAR(64) NOT NULL UNIQUE,"
                                + "payload VARCHAR(255) NOT NULL"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        }
    }

    private static SqlSessionFactory createSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 该测试不启动 Spring 容器，显式使用 JDBC 事务工厂验证 commit/rollback 语义。
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/Mq*Mapper.xml"));
        factoryBean.afterPropertiesSet();
        return Objects.requireNonNull(
                factoryBean.getObject(), "MyBatis SqlSessionFactory must be initialized");
    }

    private static DataSource createServerDataSource() {
        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        configuredDataSource.setUrl(jdbcUrl(null));
        configuredDataSource.setUsername(adminUsername);
        configuredDataSource.setPassword(adminPassword);
        return configuredDataSource;
    }

    private static DataSource createDataSource(String database) {
        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        configuredDataSource.setUrl(jdbcUrl(database));
        configuredDataSource.setUsername(adminUsername);
        configuredDataSource.setPassword(adminPassword);
        return configuredDataSource;
    }

    private static String jdbcUrl(String database) {
        String databasePart = database == null ? "" : quoteIdentifierForUrl(database);
        return "jdbc:mysql://" + adminHost + ":" + adminPort + "/" + databasePart
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=GMT%2B8"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String quoteIdentifierForUrl(String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid generated MySQL schema name");
        }
        return identifier;
    }

    private static void dropSchemaQuietly() {
        if (schemaName == null || adminUsername == null) {
            return;
        }
        try (Connection connection = createServerDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoteIdentifier(schemaName));
            log.info("已清理本机 MySQL IT schema {}", schemaName);
        } catch (Exception exception) {
            // 清理失败不能掩盖测试主体结果，但必须保留告警便于人工处理。
            log.warn("清理本机 MySQL IT schema {} 失败", schemaName, exception);
        } finally {
            schemaName = null;
        }
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
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + BUSINESS_TABLE + " (id, event_id, payload) VALUES (?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, eventId);
            statement.setString(3, "transactional-outbox-local-it");
            statement.executeUpdate();
        }
    }

    private static long countBusinessRecords(String eventId) throws SQLException {
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

    private static void expireLease(Long outboxId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE mq_outbox SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND)"
                             + " WHERE id = ?")) {
            statement.setLong(1, outboxId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void backdateMessageStatus(String messageId) throws SQLException {
        int staleSeconds = MqMessageStatusConstant.COMPENSATING_STALE_SECONDS + 1;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE mq_message_status SET update_time = DATE_SUB(NOW(), INTERVAL "
                             + staleSeconds + " SECOND) WHERE message_id = ?")) {
            statement.setString(1, messageId);
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
        String eventId = "outbox-local-" + suffix + "-"
                + UUID.randomUUID().toString().replace("-", "");
        if (eventId.length() > 64) {
            throw new IllegalStateException("Generated event ID exceeds mq_outbox.event_id limit");
        }
        return eventId;
    }

    private static void persistMessageStatus(String messageId, int status) {
        MqMessageStatus messageStatus = new MqMessageStatus();
        messageStatus.setMessageId(messageId);
        messageStatus.setEventType("integration.test");
        messageStatus.setBusinessKey(messageId);
        messageStatus.setMessageBody("payload-" + messageId);
        messageStatus.setStatus(status);
        messageStatus.setRetryCount(0);
        messageStatus.setCreateTime(new Date());
        messageStatus.setUpdateTime(new Date());

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            session.getMapper(MqMessageStatusMapper.class).insert(messageStatus);
            session.commit();
        }
    }

    private static MqMessageStatus findMessageStatus(String messageId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(MqMessageStatusMapper.class).selectByMessageId(messageId);
        }
    }

    private static String messageId(String suffix) {
        String messageId = "mq-status-local-" + suffix + "-"
                + UUID.randomUUID().toString().replace("-", "");
        if (messageId.length() > 64) {
            throw new IllegalStateException(
                    "Generated message ID exceeds mq_message_status.message_id limit");
        }
        return messageId;
    }

    private static Date dateAfterSeconds(long seconds) {
        return Date.from(Instant.now().plusSeconds(seconds));
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(value(ENABLED_PROPERTY, "MYSQL_LOCAL_IT_ENABLED", "false"));
    }

    private static String requiredValue(
            String property, String preferredEnvironment, String fallbackEnvironment) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(preferredEnvironment);
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(fallbackEnvironment);
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("缺少本机 MySQL IT 配置：-D" + property
                    + " 或 " + preferredEnvironment + "/" + fallbackEnvironment);
        }
        return configured.trim();
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured.trim();
    }

    private static int parsePort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid local MySQL port: " + rawPort, exception);
        }
    }

    private static String createSchemaName(String prefix) {
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,20}")) {
            throw new IllegalArgumentException(
                    "mysql.local.it.schema-prefix must match [A-Za-z][A-Za-z0-9_]{0,20}");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
