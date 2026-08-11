package vip.xiaozhao.intern.baseUtil.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredJsonEncoderTest {

    @Test
    void encodesStableFieldsAndDoesNotCopyArbitraryMdcEntries() {
        Logger logger = (Logger) LoggerFactory.getLogger(StructuredJsonEncoderTest.class);
        LoggingEvent event = new LoggingEvent(
                StructuredJsonEncoderTest.class.getName(), logger, Level.INFO,
                "password=top-secret request completed", null, null);
        event.setTimeStamp(0L);
        event.setMDCPropertyMap(Map.of(
                "requestId", "req-1",
                "userId", "42",
                "messageId", "msg-1",
                "eventType", "http.request",
                "durationMs", "17",
                "errorCode", "200",
                "unexpected", "should-not-be-copied"
        ));

        StructuredJsonEncoder encoder = new StructuredJsonEncoder();
        encoder.setServiceName("test-service");
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();
        JsonObject json = JsonParser.parseString(line).getAsJsonObject();

        assertEquals("1970-01-01T00:00:00Z", json.get("timestamp").getAsString());
        assertEquals("INFO", json.get("level").getAsString());
        assertEquals("test-service", json.get("service").getAsString());
        assertEquals("req-1", json.get("requestId").getAsString());
        assertEquals(17L, json.get("durationMs").getAsLong());
        assertEquals(200L, json.get("errorCode").getAsLong());
        assertFalse(json.has("unexpected"));
        assertFalse(json.get("message").getAsString().contains("top-secret"));
    }

    @Test
    void boundsMessageAndExceptionOutput() {
        Logger logger = (Logger) LoggerFactory.getLogger(StructuredJsonEncoderTest.class);
        LoggingEvent event = new LoggingEvent(
                StructuredJsonEncoderTest.class.getName(), logger, Level.ERROR,
                "message={}", new IllegalStateException("password=very-secret"),
                new Object[]{"x".repeat(100)});

        StructuredJsonEncoder encoder = new StructuredJsonEncoder();
        encoder.setMaxMessageLength(32);
        JsonObject json = JsonParser.parseString(
                new String(encoder.encode(event), StandardCharsets.UTF_8).trim()).getAsJsonObject();

        assertNotNull(json.get("exception"));
        assertTrue(json.get("message").getAsString().length() <= 32);
        assertFalse(json.get("exception").getAsString().contains("very-secret"));
    }
}
