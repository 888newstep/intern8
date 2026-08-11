package vip.xiaozhao.intern.baseUtil.logging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataSanitizerTest {

    @Test
    void sanitizeForLogMasksNestedObjectsArraysAndPhones() {
        Map<String, Object> payload = Map.of(
                "user", Map.of(
                        "phone", "13800138000",
                        "profile", List.of(Map.of("access_token", "jwt-secret"))
                ),
                "content", "public"
        );

        String sanitized = SensitiveDataSanitizer.sanitizeForLog(payload);

        assertTrue(sanitized.contains("\"phone\":\"***\""));
        assertTrue(sanitized.contains("\"access_token\":\"***\""));
        assertTrue(sanitized.contains("\"content\":\"public\""));
        assertFalse(sanitized.contains("13800138000"));
        assertFalse(sanitized.contains("jwt-secret"));
    }

    @Test
    void sanitizeTextMasksBearerAndKeyValueSecrets() {
        String sanitized = SensitiveDataSanitizer.sanitizeText(
                "Authorization: Bearer abc.def.ghi password=plain-secret phone=13800138000");

        assertFalse(sanitized.contains("abc.def.ghi"));
        assertFalse(sanitized.contains("plain-secret"));
        assertFalse(sanitized.contains("13800138000"));
        assertTrue(sanitized.contains("Bearer ***"));
        assertTrue(sanitized.contains("password=***"));
    }

    @Test
    void truncateIncludesSuffixWithinConfiguredLimit() {
        String sanitized = SensitiveDataSanitizer.truncate("01234567890123456789", 10);

        assertTrue(sanitized.length() <= 10);
        assertTrue(sanitized.endsWith("...(tr"));
    }
}
