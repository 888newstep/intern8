package vip.xiaozhao.intern.baseUtil.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnCallNotPermittedEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.config.CircuitBreakerProperties;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 为外部依赖提供统一的熔断、降级和观测入口。
 *
 * <p>熔断阈值由 {@code resilience.circuit-breakers.*} 配置驱动，避免不同业务
 * 在代码中各自维护一套不可审计的阈值。</p>
 */
@Service
public class CircuitBreakerService {

    private static final String REDIS = "redis";
    private static final String RABBIT_MQ = "rabbitMq";
    private static final String COS = "cos";

    private static final String STATE_METRIC = "resilience.circuit_breaker.state";
    private static final String TRANSITION_METRIC = "resilience.circuit_breaker.state.transitions";
    private static final String FALLBACK_METRIC = "resilience.circuit_breaker.fallbacks";
    private static final String REJECTED_METRIC = "resilience.circuit_breaker.calls.not_permitted";
    private static final String PROBE_METRIC = "resilience.circuit_breaker.half_open.probes";
    private static final String PROBE_SUCCESS_METRIC = "resilience.circuit_breaker.half_open.successes";
    private static final String PROBE_FAILURE_METRIC = "resilience.circuit_breaker.half_open.failures";

    private final CircuitBreaker redisCircuitBreaker;
    private final CircuitBreaker rabbitMqCircuitBreaker;
    private final CircuitBreaker cosCircuitBreaker;
    private final MeterRegistry meterRegistry;

    @Autowired
    public CircuitBreakerService(MeterRegistry meterRegistry, CircuitBreakerProperties properties) {
        this.meterRegistry = meterRegistry;
        CircuitBreakerProperties.Settings redisSettings = properties.getRedis();
        CircuitBreakerProperties.Settings rabbitMqSettings = properties.getRabbitMq();
        CircuitBreakerProperties.Settings cosSettings = properties.getCos();
        redisSettings.validate(REDIS);
        rabbitMqSettings.validate(RABBIT_MQ);
        cosSettings.validate(COS);

        CircuitBreakerConfig redisConfig = buildConfig(redisSettings);
        CircuitBreakerConfig rabbitMqConfig = buildConfig(rabbitMqSettings);
        CircuitBreakerConfig cosConfig = buildConfig(cosSettings);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(redisConfig);
        this.redisCircuitBreaker = registry.circuitBreaker(REDIS, redisConfig);
        this.rabbitMqCircuitBreaker = registry.circuitBreaker(RABBIT_MQ, rabbitMqConfig);
        this.cosCircuitBreaker = registry.circuitBreaker(COS, cosConfig);

        bindMetrics(REDIS, redisCircuitBreaker);
        bindMetrics(RABBIT_MQ, rabbitMqCircuitBreaker);
        bindMetrics(COS, cosCircuitBreaker);
    }

    /** 保留旧构造函数，方便现有纯单元测试和非 Spring 调用��平滑迁移。 */
    public CircuitBreakerService(MeterRegistry meterRegistry) {
        this(meterRegistry, new CircuitBreakerProperties());
    }

    public <T> T executeWithRedisBreaker(Supplier<T> operation, Supplier<T> fallback) {
        return execute(REDIS, redisCircuitBreaker, operation, fallback);
    }

    public void executeWithRedisBreaker(Runnable operation, Runnable fallback) {
        execute(REDIS, redisCircuitBreaker, operation, fallback);
    }

    public <T> T executeWithMqBreaker(Supplier<T> operation, Supplier<T> fallback) {
        return execute(RABBIT_MQ, rabbitMqCircuitBreaker, operation, fallback);
    }

    public void executeWithMqBreaker(Runnable operation, Runnable fallback) {
        execute(RABBIT_MQ, rabbitMqCircuitBreaker, operation, fallback);
    }

    public <T> T executeWithCosBreaker(Supplier<T> operation, Supplier<T> fallback) {
        return execute(COS, cosCircuitBreaker, operation, fallback);
    }

    public void executeWithCosBreaker(Runnable operation, Runnable fallback) {
        execute(COS, cosCircuitBreaker, operation, fallback);
    }

    public CircuitBreaker.State getRedisState() {
        return redisCircuitBreaker.getState();
    }

    public CircuitBreaker.State getMqState() {
        return rabbitMqCircuitBreaker.getState();
    }

    public CircuitBreaker.State getCosState() {
        return cosCircuitBreaker.getState();
    }

    public void recordMqFailure(Throwable failure) {
        Throwable actualFailure = failure == null
                ? new IllegalStateException("RabbitMQ publisher failure")
                : failure;
        rabbitMqCircuitBreaker.onError(0, TimeUnit.NANOSECONDS, actualFailure);
    }

    private <T> T execute(String dependency,
                          CircuitBreaker circuitBreaker,
                          Supplier<T> operation,
                          Supplier<T> fallback) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (Exception exception) {
            fallbackCounter(dependency).increment();
            logFallback(dependency, circuitBreaker, exception);
            return fallback.get();
        }
    }

    private void execute(String dependency,
                         CircuitBreaker circuitBreaker,
                         Runnable operation,
                         Runnable fallback) {
        try {
            circuitBreaker.executeRunnable(operation);
        } catch (Exception exception) {
            fallbackCounter(dependency).increment();
            logFallback(dependency, circuitBreaker, exception);
            fallback.run();
        }
    }

    private static CircuitBreakerConfig buildConfig(CircuitBreakerProperties.Settings settings) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.getFailureRateThreshold())
                .slowCallRateThreshold(settings.getSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(settings.getSlowCallDurationThresholdMs()))
                .waitDurationInOpenState(Duration.ofMillis(settings.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .recordExceptions(RuntimeException.class);
        if (settings.isAutomaticTransitionFromOpenToHalfOpen()) {
            builder.enableAutomaticTransitionFromOpenToHalfOpen();
        }
        return builder.build();
    }

    private void bindMetrics(String dependency, CircuitBreaker circuitBreaker) {
        Gauge.builder(STATE_METRIC, circuitBreaker, breaker -> stateValue(breaker.getState()))
                .description("Current circuit breaker state: 0=closed, 1=open, 2=half_open")
                .tag("dependency", dependency)
                .register(meterRegistry);

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> recordStateTransition(dependency, event))
                .onCallNotPermitted(event -> recordCallNotPermitted(dependency, event));
    }

    private void recordStateTransition(String dependency, CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.StateTransition transition = event.getStateTransition();
        Counter.builder(TRANSITION_METRIC)
                .tag("dependency", dependency)
                .tag("from", transition.getFromState().name())
                .tag("to", transition.getToState().name())
                .register(meterRegistry)
                .increment();

        if (transition == CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN) {
            counter(PROBE_METRIC, dependency).increment();
        } else if (transition == CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED) {
            counter(PROBE_SUCCESS_METRIC, dependency).increment();
        } else if (transition == CircuitBreaker.StateTransition.HALF_OPEN_TO_OPEN) {
            counter(PROBE_FAILURE_METRIC, dependency).increment();
        }
    }

    private void recordCallNotPermitted(String dependency, CircuitBreakerOnCallNotPermittedEvent event) {
        counter(REJECTED_METRIC, dependency).increment();
    }

    private Counter fallbackCounter(String dependency) {
        return counter(FALLBACK_METRIC, dependency);
    }

    private Counter counter(String metricName, String dependency) {
        return Counter.builder(metricName)
                .tag("dependency", dependency)
                .register(meterRegistry);
    }

    private void logFallback(String dependency, CircuitBreaker circuitBreaker, Exception exception) {
        logger().warn("Circuit breaker fallback, dependency={}, state={}, cause={}",
                dependency,
                circuitBreaker.getState(),
                exception.getClass().getSimpleName());
    }

    private static int stateValue(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN, FORCED_OPEN -> 1;
            case HALF_OPEN -> 2;
            case DISABLED -> 3;
            case METRICS_ONLY -> 4;
        };
    }

    private static org.slf4j.Logger logger() {
        return org.slf4j.LoggerFactory.getLogger(CircuitBreakerService.class);
    }
}
