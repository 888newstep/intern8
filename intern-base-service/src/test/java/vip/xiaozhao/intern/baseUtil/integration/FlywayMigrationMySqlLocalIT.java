package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从空 schema 验证生产 Flyway 迁移。
 *
 * <p>测试默认关闭。显式开启后只创建随机隔离 schema，不读取、清空或删除项目已有的
 * {@code xiao} 数据库；测试结束会尝试删除本次创建的 schema。</p>
 */
class FlywayMigrationMySqlLocalIT {

    private static final Logger logger = LoggerFactory.getLogger(FlywayMigrationMySqlLocalIT.class);

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

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "tui_dynamic",
            "tui_follow",
            "tui_notification",
            "tui_comment",
            "tui_like",
            "mq_message_status",
            "mq_outbox");

    private static final Set<String> EXPECTED_INDEXES = Set.of(
            "uk_user_follow",
            "idx_follow_covering",
            "idx_dynamic_status_id",
            "idx_user_dynamics",
            "idx_notification_list",
            "idx_notification_user_id",
            "idx_comment_list",
            "uk_mq_outbox_event_id");

    private static DataSource dataSource;
    private static String adminHost;
    private static int adminPort;
    private static String adminUsername;
    private static String adminPassword;
    private static String schemaName;

    @BeforeAll
    static void initializeEmptySchema() throws Exception {
        Assumptions.assumeTrue(isEnabled(),
                "本机 Flyway MySQL IT 默认关闭，请显式设置 -D" + ENABLED_PROPERTY + "=true");

        adminHost = value(HOST_PROPERTY, HOST_ENV, "127.0.0.1");
        adminPort = parsePort(value(PORT_PROPERTY, PORT_ENV, "3306"));
        adminUsername = requiredValue(USERNAME_PROPERTY, USERNAME_ENV, FALLBACK_USERNAME_ENV);
        adminPassword = requiredValue(PASSWORD_PROPERTY, PASSWORD_ENV, FALLBACK_PASSWORD_ENV);
        schemaName = createSchemaName(value(SCHEMA_PREFIX_PROPERTY, SCHEMA_PREFIX_ENV, "intern_flyway"));

        createSchema();
        dataSource = createDataSource(schemaName);
        try {
            FlywayMigrationSupport.migrate(dataSource);
        } catch (Exception exception) {
            dropSchemaQuietly();
            throw exception;
        }
        logger.info("本机 Flyway MySQL IT 已启用，使用隔离 schema {}", schemaName);
    }

    @AfterAll
    static void dropTestSchema() {
        dropSchemaQuietly();
    }

    @Test
    void emptySchemaShouldApplyProductionMigrations() throws SQLException {
        assertEquals(List.of("1:init:true", "2:indexes:true", "3:notification cursor index:true"), migrationHistory());
        assertEquals(EXPECTED_TABLES, tableNames());
        Set<String> actualIndexes = indexNames();
        assertTrue(actualIndexes.containsAll(EXPECTED_INDEXES),
                () -> "Missing production indexes: " + difference(EXPECTED_INDEXES, actualIndexes));
        assertEquals(List.of("status", "id", "user_id"),
                indexColumns("tui_dynamic", "idx_dynamic_status_id"));
    }

    @Test
    void rerunningProductionMigrationsShouldBeNoOp() throws SQLException {
        FlywayMigrationSupport.migrate(dataSource);
        assertEquals(3, migrationHistory().size());
        assertEquals(EXPECTED_TABLES, tableNames());
    }

    private static List<String> migrationHistory() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")) {
            List<String> history = new ArrayList<>();
            while (resultSet.next()) {
                history.add(resultSet.getString("version") + ":"
                        + resultSet.getString("description") + ":"
                        + resultSet.getBoolean("success"));
            }
            return history;
        }
    }

    private static Set<String> tableNames() throws SQLException {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables"
                             + " WHERE table_schema = DATABASE()"
                             + " AND table_type = 'BASE TABLE'"
                             + " AND table_name <> 'flyway_schema_history'")) {
            Set<String> tables = new HashSet<>();
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
            return tables;
        }
    }

    private static Set<String> indexNames() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT DISTINCT index_name FROM information_schema.statistics"
                             + " WHERE table_schema = DATABASE()")) {
            Set<String> indexes = new HashSet<>();
            while (resultSet.next()) {
                indexes.add(resultSet.getString(1));
            }
            return indexes;
        }
    }

    private static List<String> indexColumns(String tableName, String indexName) throws SQLException {
        String sql = "SELECT column_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?"
                + " ORDER BY seq_in_index";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                return columns;
            }
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static void createSchema() throws SQLException {
        try (Connection connection = createServerDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoteIdentifier(schemaName)
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static DataSource createServerDataSource() {
        return createDataSource(null);
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
            logger.info("已清理本机 Flyway MySQL IT schema {}", schemaName);
        } catch (Exception exception) {
            logger.warn("清理本机 Flyway MySQL IT schema {} 失败", schemaName, exception);
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
}
