package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.config.CircuitBreakerProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerServiceTest {

    @Test
    void rabbitMqFailuresShouldOpenBreakerAndCountFallbacksAndRejectedCalls() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerService service = new CircuitBreakerService(meterRegistry, testProperties());

        for (int i = 0; i < 4; i++) {
            String result = service.executeWithMqBreaker(
                    () -> {
                        throw new IllegalStateException("broker unavailable");
                    },
                    () -> "fallback");
            assertEquals("fallback", result);
        }

        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                service.getMqState());

        assertEquals("fallback", service.executeWithMqBreaker(
                () -> "must-not-run",
                () -> "fallback"));

        assertEquals(5.0, meterRegistry.get("resilience.circuit_breaker.fallbacks")
                .tag("dependency", "rabbitMq")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("resilience.circuit_breaker.calls.not_permitted")
                .tag("dependency", "rabbitMq")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("resilience.circuit_breaker.state.transitions")
                .tags("dependency", "rabbitMq", "from", "CLOSED", "to", "OPEN")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("resilience.circuit_breaker.state")
                .tag("dependency", "rabbitMq")
                .gauge()
                .value());
    }

    @Test
    void successfulHalfOpenProbeShouldCloseBreakerAndRecordRecovery() throws InterruptedException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerService service = new CircuitBreakerService(meterRegistry, testProperties());

        for (int i = 0; i < 4; i++) {
            service.executeWithMqBreaker(
                    () -> {
                        throw new IllegalStateException("temporary broker failure");
                    },
                    () -> false);
        }
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                service.getMqState());

        Thread.sleep(150);
        assertTrue(service.executeWithMqBreaker(() -> true, () -> false));

        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                service.getMqState());
        assertEquals(1.0, meterRegistry.get("resilience.circuit_breaker.half_open.probes")
                .tag("dependency", "rabbitMq")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("resilience.circuit_breaker.half_open.successes")
                .tag("dependency", "rabbitMq")
                .counter()
                .count());
        assertEquals(0.0, meterRegistry.get("resilience.circuit_breaker.state")
                .tag("dependency", "rabbitMq")
                .gauge()
                .value());
    }

    private static CircuitBreakerProperties testProperties() {
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setRabbitMq(new CircuitBreakerProperties.Settings(
                50,
                100,
                Duration.ofSeconds(10).toMillis(),
                100,
                1,
                4,
                4,
                true));
        return properties;
    }
}
