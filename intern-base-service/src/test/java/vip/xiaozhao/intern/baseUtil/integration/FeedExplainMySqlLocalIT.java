package vip.xiaozhao.intern.baseUtil.integration;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在本机 MySQL 隔离 schema 中验证 Feed SQL 的 EXPLAIN 变化。
 *
 * <p>该测试只提供小规模 smoke evidence，不代表生产规模的 QPS、P99 或最终索引方案。</p>
 */
class FeedExplainMySqlLocalIT {

    private static final Logger log = LoggerFactory.getLogger(FeedExplainMySqlLocalIT.class);

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

    private static final long FEED_USER_ID = 100L;
    private static final long FEED_CURSOR = 50_001L;
    private static final int FEED_LIMIT = 20;
    private static final int FOLLOW_COUNT = 5_000;
    private static final int DYNAMIC_COUNT = 50_000;

    private static final String FEED_EXPLAIN_SQL = """
            EXPLAIN
            SELECT d.id
            FROM tui_dynamic d
            WHERE d.status = 0
              AND d.id < 50001
              AND EXISTS (
                  SELECT 1
                  FROM tui_follow f
                  WHERE f.user_id = 100
                    AND f.status = 0
                    AND f.follow_user_id = d.user_id
              )
            ORDER BY d.id DESC
            LIMIT 20
            """;

    private static final String FEED_STRAIGHT_JOIN_EXPLAIN_SQL = """
            EXPLAIN
            SELECT d.id
            FROM tui_dynamic d
            STRAIGHT_JOIN tui_follow f
              ON f.follow_user_id = d.user_id
             AND f.user_id = 100
             AND f.status = 0
            WHERE d.status = 0
              AND d.id < 50001
            ORDER BY d.id DESC
            LIMIT 20
            """;

    private static final String FEED_HINTED_JOIN_EXPLAIN_SQL = """
            EXPLAIN
            SELECT /*+ JOIN_ORDER(d, f) */ d.id
            FROM tui_dynamic d FORCE INDEX (idx_dynamic_status_id)
            INNER JOIN tui_follow f FORCE INDEX (idx_follow_covering)
              ON f.follow_user_id = d.user_id
             AND f.user_id = 100
             AND f.status = 0
            WHERE d.status = 0
              AND d.id < 50001
            ORDER BY d.id DESC
            LIMIT 20
            """;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static String schemaName;
    private static String adminHost;
    private static int adminPort;
    private static String adminUsername;
    private static String adminPassword;

    @BeforeAll
    static void initializeSchemaAndData() throws Exception {
        Assumptions.assumeTrue(isEnabled(),
                "本机 MySQL EXPLAIN IT 默认关闭，请显式设置 -D" + ENABLED_PROPERTY + "=true");

        adminHost = value(HOST_PROPERTY, HOST_ENV, "127.0.0.1");
        adminPort = parsePort(value(PORT_PROPERTY, PORT_ENV, "3306"));
        adminUsername = requiredValue(USERNAME_PROPERTY, USERNAME_ENV, FALLBACK_USERNAME_ENV);
        adminPassword = requiredValue(PASSWORD_PROPERTY, PASSWORD_ENV, FALLBACK_PASSWORD_ENV);
        schemaName = createSchemaName(value(
                SCHEMA_PREFIX_PROPERTY, SCHEMA_PREFIX_ENV, "intern_feed_it"));

        createSchema();
        dataSource = createDataSource(schemaName);
        try {
            createBaselineTables();
            seedData();
            sqlSessionFactory = createSqlSessionFactory();
        } catch (Exception exception) {
            dropSchemaQuietly();
            throw exception;
        }
        log.info("本机 MySQL Feed EXPLAIN IT 已启用，使用隔离 schema {}", schemaName);
    }

    @AfterAll
    static void dropTestSchema() {
        dropSchemaQuietly();
    }

    @Test
    void feedPlanShouldExposeOptimizationIndexesAfterIndexChange() throws Exception {
        List<ExplainRow> before = explainFeed();
        logPlan("before", before);

        createOptimizationIndexes();
        analyzeTables();

        List<ExplainRow> after = explainFeed();
        logPlan("after", after);
        logPlan("straight-join", explainFeed(FEED_STRAIGHT_JOIN_EXPLAIN_SQL));
        logPlan("hinted-join", explainFeed(FEED_HINTED_JOIN_EXPLAIN_SQL));

        ExplainRow dynamicPlan = rowFor(after, "d");
        assertNotNull(dynamicPlan);
        assertTrue(containsIndex(dynamicPlan, "idx_dynamic_status_id"),
                () -> "Feed dynamic plan did not expose idx_dynamic_status_id: " + dynamicPlan);
        assertTrue(after.stream().anyMatch(row -> "f".equals(row.table())
                        && containsIndex(row, "idx_follow_covering")),
                () -> "Feed EXISTS plan did not expose idx_follow_covering: " + after);
    }

    @Test
    void productionTwoStageMapperShouldReturnDescendingCursorPage() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            TuiDynamicMapper mapper = session.getMapper(TuiDynamicMapper.class);
            List<Long> ids = mapper.selectFeedDynamicIds(FEED_USER_ID, FEED_CURSOR, FEED_LIMIT);

            assertTrue(ids.size() <= FEED_LIMIT);
            assertTrue(isStrictlyDescending(ids),
                    () -> "Feed IDs are not cursor ordered: " + ids);
            assertTrue(ids.stream().allMatch(id -> id < FEED_CURSOR));

            List<TuiDynamic> dynamics = mapper.selectByIds(ids);
            assertTrue(dynamics.stream().allMatch(dynamic -> dynamic.getStatus() == 0));
            assertTrue(dynamics.stream().allMatch(dynamic -> ids.contains(dynamic.getId())));
            assertTrue(mapper.selectByIds(List.of()).isEmpty(),
                    "Empty ID batch must not generate an invalid IN () query");
        }
    }

    private static void createSchema() throws SQLException {
        try (Connection connection = createServerDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoteIdentifier(schemaName)
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static SqlSessionFactory createSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/TuiDynamicMapper.xml"));
        factoryBean.afterPropertiesSet();
        return Objects.requireNonNull(
                factoryBean.getObject(), "MyBatis SqlSessionFactory must be initialized");
    }

    private static void createBaselineTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE tui_dynamic (
                        id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        content VARCHAR(128) NOT NULL,
                        images VARCHAR(256) NULL,
                        like_count INT NOT NULL DEFAULT 0,
                        comment_count INT NOT NULL DEFAULT 0,
                        share_count INT NOT NULL DEFAULT 0,
                        create_time DATETIME NOT NULL,
                        update_time DATETIME NOT NULL,
                        status TINYINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        KEY idx_user_id (user_id),
                        KEY idx_user_status (user_id, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE tui_follow (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        user_id BIGINT NOT NULL,
                        follow_user_id BIGINT NOT NULL,
                        create_time DATETIME NOT NULL,
                        status TINYINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_user_follow (user_id, follow_user_id),
                        KEY idx_follow_user_id (follow_user_id),
                        KEY idx_user_status (user_id, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    private static void seedData() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement follow = connection.prepareStatement(
                     "INSERT INTO tui_follow "
                             + "(user_id, follow_user_id, create_time, status) VALUES (?, ?, NOW(), ?)");
             PreparedStatement dynamic = connection.prepareStatement(
                     "INSERT INTO tui_dynamic "
                             + "(id, user_id, content, images, like_count, comment_count, share_count, "
                             + "create_time, update_time, status) VALUES (?, ?, ?, NULL, 0, 0, 0, NOW(), NOW(), ?)");) {
            connection.setAutoCommit(false);
            for (int i = 0; i < FOLLOW_COUNT; i++) {
                follow.setLong(1, FEED_USER_ID);
                follow.setLong(2, 1_000L + i);
                follow.setInt(3, 0);
                follow.addBatch();
                if ((i + 1) % 1_000 == 0) {
                    follow.executeBatch();
                }
            }
            follow.executeBatch();

            for (int i = 1; i <= DYNAMIC_COUNT; i++) {
                long authorId = 1_000L + ((i - 1) % (FOLLOW_COUNT * 2));
                dynamic.setLong(1, i);
                dynamic.setLong(2, authorId);
                dynamic.setString(3, "feed-explain-" + i);
                dynamic.setInt(4, i % 20 == 0 ? 1 : 0);
                dynamic.addBatch();
                if (i % 2_000 == 0) {
                    dynamic.executeBatch();
                }
            }
            dynamic.executeBatch();
            connection.commit();
        }
    }

    private static List<ExplainRow> explainFeed() throws SQLException {
        return explainFeed(FEED_EXPLAIN_SQL);
    }

    private static List<ExplainRow> explainFeed(String explainSql) throws SQLException {
        List<ExplainRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(explainSql)) {
            while (resultSet.next()) {
                rows.add(new ExplainRow(
                        resultSet.getString("table"),
                        resultSet.getString("type"),
                        resultSet.getString("possible_keys"),
                        resultSet.getString("key"),
                        resultSet.getLong("rows"),
                        resultSet.getString("Extra")));
            }
        }
        return rows;
    }

    private static void createOptimizationIndexes() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE INDEX idx_follow_covering ON tui_follow (user_id, status, follow_user_id)");
            statement.executeUpdate(
                    "CREATE INDEX idx_dynamic_status_id ON tui_dynamic (status, id DESC)");
        }
    }

    private static void analyzeTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE TABLE tui_follow, tui_dynamic");
        }
    }

    private static ExplainRow rowFor(List<ExplainRow> rows, String table) {
        return rows.stream().filter(row -> table.equals(row.table())).findFirst().orElse(null);
    }

    private static boolean isStrictlyDescending(List<Long> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1) <= values.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsIndex(ExplainRow row, String indexName) {
        return indexName.equals(row.key())
                || (row.possibleKeys() != null && row.possibleKeys().contains(indexName));
    }

    private static void logPlan(String phase, List<ExplainRow> rows) {
        for (ExplainRow row : rows) {
            log.info("Feed EXPLAIN {}: table={}, type={}, possibleKeys={}, key={}, rows={}, extra={}",
                    phase, row.table(), row.type(), row.possibleKeys(), row.key(), row.rows(), row.extra());
        }
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

    private static void dropSchemaQuietly() {
        if (schemaName == null || adminUsername == null) {
            return;
        }
        try (Connection connection = createServerDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoteIdentifier(schemaName));
            log.info("已清理本机 MySQL Feed EXPLAIN IT schema {}", schemaName);
        } catch (Exception exception) {
            log.warn("清理本机 MySQL Feed EXPLAIN IT schema {} 失败", schemaName, exception);
        } finally {
            schemaName = null;
        }
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

    private static String createSchemaName(String prefix) {
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,20}")) {
            throw new IllegalArgumentException(
                    "mysql.local.it.schema-prefix must match [A-Za-z][A-Za-z0-9_]{0,20}");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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

    private record ExplainRow(
            String table,
            String type,
            String possibleKeys,
            String key,
            long rows,
            String extra) {
    }
}
