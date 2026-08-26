package vip.xiaozhao.intern.baseUtil.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationContractTest {

    private static final Pattern VERSIONED_FILE =
            Pattern.compile("V(\\d+)__([A-Za-z0-9_-]+)\\.sql");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?");

    @Test
    void migrationHistoryShouldBeVersionedAndDeterministic() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");

        assertEquals(3, resources.length, "V1, V2 and V3 must be present");

        Map<Integer, String> sqlByVersion = new HashMap<>();
        Set<String> tableNames = new HashSet<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            assertNotNull(filename, "Migration filename must be available");
            Matcher filenameMatcher = VERSIONED_FILE.matcher(filename);
            assertTrue(filenameMatcher.matches(), "Invalid migration filename: " + filename);

            int version = Integer.parseInt(filenameMatcher.group(1));
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(sql.matches("(?is).*CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS.*"),
                    "Migrations must fail on partial schema instead of masking drift: " + filename);
            assertTrue(sqlByVersion.put(version, sql) == null,
                    "Duplicate migration version: " + version);

            Matcher tableMatcher = CREATE_TABLE.matcher(sql);
            while (tableMatcher.find()) {
                assertTrue(tableNames.add(tableMatcher.group(1).toLowerCase(Locale.ROOT)),
                        "A table must be created by only one migration: " + tableMatcher.group(1));
            }
        }

        List<Integer> versions = new ArrayList<>(sqlByVersion.keySet());
        versions.sort(Integer::compareTo);
        assertEquals(List.of(1, 2, 3), versions);

        String v1 = sqlByVersion.get(1);
        assertTrue(v1.contains("CREATE TABLE `tui_dynamic`"));
        assertTrue(v1.contains("CREATE TABLE `tui_follow`"));
        assertTrue(v1.contains("CREATE TABLE `tui_notification`"));
        assertTrue(v1.contains("CREATE TABLE `tui_comment`"));
        assertTrue(v1.contains("CREATE TABLE `tui_like`"));
        assertTrue(v1.contains("CREATE TABLE `mq_message_status`"));
        assertTrue(v1.contains("CREATE TABLE `mq_outbox`"));
        assertTrue(v1.contains("UNIQUE INDEX `uk_user_follow`"));

        String v2 = sqlByVersion.get(2);
        assertTrue(v2.contains("CREATE INDEX `idx_follow_covering`"));
        assertTrue(v2.contains("CREATE INDEX `idx_dynamic_status_id`"));
        assertTrue(v2.contains("ON `tui_dynamic` (`status`, `id` DESC, `user_id`)"),
                "Feed cursor index must cover the join author ID");
        assertTrue(v2.contains("CREATE INDEX `idx_user_dynamics`"));
        assertTrue(v2.contains("CREATE INDEX `idx_notification_list`"));
        assertTrue(v2.contains("CREATE INDEX `idx_comment_list`"));

        String v3 = sqlByVersion.get(3);
        assertTrue(v3.contains("CREATE INDEX `idx_notification_user_id`"));
        assertTrue(v3.contains("ON `tui_notification` (`user_id`, `id` DESC)"));
    }
}
