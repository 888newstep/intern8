package vip.xiaozhao.intern.baseUtil.logging;

import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits one JSON object per line with a stable, intentionally small field set.
 * This avoids leaking arbitrary MDC entries and keeps log volume bounded.
 */
public class StructuredJsonEncoder extends JsonEncoder {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_EXCEPTION_LENGTH = 8192;

    private String serviceName = "intern-base";
    private int maxMessageLength = SensitiveDataSanitizer.DEFAULT_MAX_LENGTH;

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> record = new LinkedHashMap<>();
        Map<String, String> mdc = event.getMDCPropertyMap();

        record.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        record.put("level", event.getLevel() == null ? null : event.getLevel().toString());
        record.put("service", serviceName);
        record.put("requestId", sanitizeMdc(mdc, "requestId"));
        record.put("userId", sanitizeMdc(mdc, "userId"));
        record.put("messageId", sanitizeMdc(mdc, "messageId"));
        record.put("eventType", sanitizeMdc(mdc, "eventType"));
        record.put("durationMs", numericMdc(mdc, "durationMs"));
        record.put("errorCode", numericMdc(mdc, "errorCode"));
        record.put("httpMethod", sanitizeMdc(mdc, "httpMethod"));
        record.put("requestUri", sanitizeMdc(mdc, "requestUri"));
        record.put("thread", event.getThreadName());
        record.put("logger", event.getLoggerName());
        record.put("message", SensitiveDataSanitizer.sanitizeText(
                event.getFormattedMessage(), maxMessageLength));

        if (event.getThrowableProxy() != null) {
            record.put("exception", SensitiveDataSanitizer.sanitizeText(
                    ThrowableProxyUtil.asString(event.getThrowableProxy()), MAX_EXCEPTION_LENGTH));
        }

        return (GSON.toJson(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    public void setServiceName(String serviceName) {
        if (serviceName != null && !serviceName.isBlank()) {
            this.serviceName = serviceName;
        }
    }

    public void setMaxMessageLength(int maxMessageLength) {
        if (maxMessageLength > 0) {
            this.maxMessageLength = Math.min(maxMessageLength, 16 * 1024);
        }
    }

    private String sanitizeMdc(Map<String, String> mdc, String key) {
        if (mdc == null) {
            return null;
        }
        return SensitiveDataSanitizer.sanitizeText(mdc.get(key), maxMessageLength);
    }

    private Number numericMdc(Map<String, String> mdc, String key) {
        String value = mdc == null ? null : mdc.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
