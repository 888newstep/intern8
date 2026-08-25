package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqMessageStatusMapperContractTest {

    @Test
    void compensationSqlShouldRecoverStalePendingAndCompensatingMessages() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:mapper/MqMessageStatusMapper.xml");
        assertTrue(resource.exists(), "MqMessageStatusMapper.xml must be available on the test classpath");

        String mapperXml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertEquals(2, countOccurrences(mapperXml, "status IN (2, 4)"));
        assertEquals(2, countOccurrences(mapperXml, "status IN (0, 5)"));
        assertEquals(2, countOccurrences(mapperXml,
                "TIMESTAMPDIFF(SECOND, update_time, NOW()) &gt;= #{staleSeconds}"));
        assertFalse(mapperXml.contains("status = 5 AND TIMESTAMPDIFF"));
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
