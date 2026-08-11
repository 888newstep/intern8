package vip.xiaozhao.intern.baseUtil.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces bounded log-safe representations without mutating application objects.
 * Sensitive keys are masked recursively, including nested arrays and bean fields.
 */
public final class SensitiveDataSanitizer {

    public static final String MASKED_VALUE = "***";
    public static final int DEFAULT_MAX_LENGTH = 2048;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password", "token", "secret", "authorization", "accesskey",
            "credential", "cookie", "phone", "mobile", "telephone"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)([\\\"']?[A-Za-z0-9_-]*(?:password|token|secret|authorization|accesskey|credential|cookie|phone|mobile|telephone)[A-Za-z0-9_-]*[\\\"']?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^,}\\]\\s]+)"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitizeForLog(Object value) {
        return sanitizeForLog(value, DEFAULT_MAX_LENGTH);
    }

    public static String sanitizeForLog(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>) {
            return truncate(sanitizeText(String.valueOf(value)), maxLength);
        }

        try {
            JsonElement sanitized = sanitizeJsonElement(GSON.toJsonTree(value));
            return truncate(GSON.toJson(sanitized), maxLength);
        } catch (RuntimeException ex) {
            // Do not fall back to an arbitrary toString(): it may contain credentials.
            return truncate("<unserializable:" + value.getClass().getSimpleName() + ">", maxLength);
        }
    }

    public static String sanitizeText(String value) {
        return sanitizeText(value, DEFAULT_MAX_LENGTH);
    }

    public static String sanitizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String sanitized = BEARER_PATTERN.matcher(value).replaceAll("$1" + MASKED_VALUE);
        sanitized = maskKeyValuePairs(sanitized);
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll(MASKED_VALUE);
        return truncate(sanitized, maxLength);
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return value == null ? null : "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        String suffix = "...(truncated)";
        if (maxLength < suffix.length()) {
            int markerLength = Math.min(maxLength, 6);
            return value.substring(0, maxLength - markerLength)
                    + suffix.substring(0, markerLength);
        }
        return value.substring(0, maxLength - suffix.length()) + suffix;
    }

    private static JsonElement sanitizeJsonElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (isSensitiveKey(entry.getKey())) {
                    entry.setValue(new JsonPrimitive(MASKED_VALUE));
                } else {
                    entry.setValue(sanitizeJsonElement(entry.getValue()));
                }
            }
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitizeJsonElement(array.get(i)));
            }
            return array;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(sanitizeText(element.getAsString(), Integer.MAX_VALUE));
        }
        return element;
    }

    private static String maskKeyValuePairs(String value) {
        Matcher matcher = KEY_VALUE_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer(value.length());
        while (matcher.find()) {
            String rawValue = matcher.group(2);
            String replacementValue = MASKED_VALUE;
            int nextIndex = matcher.end();
            while (nextIndex < value.length() && Character.isWhitespace(value.charAt(nextIndex))) {
                nextIndex++;
            }
            if (rawValue.equalsIgnoreCase("Bearer")
                    && value.startsWith(MASKED_VALUE, nextIndex)) {
                // Preserve the scheme after the bearer token was masked before key-value processing.
                replacementValue = rawValue;
            } else if (rawValue.length() >= 2) {
                char first = rawValue.charAt(0);
                char last = rawValue.charAt(rawValue.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    replacementValue = first + MASKED_VALUE + last;
                }
            }
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + replacementValue));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
