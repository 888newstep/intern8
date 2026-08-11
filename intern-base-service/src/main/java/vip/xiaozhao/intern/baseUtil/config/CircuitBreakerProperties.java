package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 熔断策略配置。配置放在 resilience.circuit-breakers 下，避免业务代码硬编码阈值。
 */
@Component
@ConfigurationProperties(prefix = "resilience.circuit-breakers")
public class CircuitBreakerProperties {

    private Settings redis = Settings.redisDefaults();
    private Settings rabbitMq = Settings.rabbitMqDefaults();
    private Settings cos = Settings.cosDefaults();

    public Settings getRedis() {
        return redis;
    }

    public void setRedis(Settings redis) {
        this.redis = requireSettings(redis, "redis");
    }

    public Settings getRabbitMq() {
        return rabbitMq;
    }

    public void setRabbitMq(Settings rabbitMq) {
        this.rabbitMq = requireSettings(rabbitMq, "rabbitMq");
    }

    public Settings getCos() {
        return cos;
    }

    public void setCos(Settings cos) {
        this.cos = requireSettings(cos, "cos");
    }

    private static Settings requireSettings(Settings settings, String dependency) {
        if (settings == null) {
            throw new IllegalArgumentException("Circuit breaker settings cannot be null: " + dependency);
        }
        return settings;
    }

    public static class Settings {

        private float failureRateThreshold;
        private float slowCallRateThreshold;
        private long slowCallDurationThresholdMs;
        private long waitDurationInOpenStateMs;
        private int permittedNumberOfCallsInHalfOpenState;
        private int slidingWindowSize;
        private int minimumNumberOfCalls;
        private boolean automaticTransitionFromOpenToHalfOpen;

        public static Settings redisDefaults() {
            return new Settings(50, 80, 5_000, 30_000, 3, 10, 5, true);
        }

        public static Settings rabbitMqDefaults() {
            return new Settings(60, 80, 5_000, 60_000, 5, 10, 5, true);
        }

        public static Settings cosDefaults() {
            return new Settings(50, 80, 10_000, 60_000, 3, 10, 5, true);
        }

        public Settings() {
        }

        public Settings(float failureRateThreshold,
                        float slowCallRateThreshold,
                        long slowCallDurationThresholdMs,
                        long waitDurationInOpenStateMs,
                        int permittedNumberOfCallsInHalfOpenState,
                        int slidingWindowSize,
                        int minimumNumberOfCalls,
                        boolean automaticTransitionFromOpenToHalfOpen) {
            this.failureRateThreshold = failureRateThreshold;
            this.slowCallRateThreshold = slowCallRateThreshold;
            this.slowCallDurationThresholdMs = slowCallDurationThresholdMs;
            this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
            this.slidingWindowSize = slidingWindowSize;
            this.minimumNumberOfCalls = minimumNumberOfCalls;
            this.automaticTransitionFromOpenToHalfOpen = automaticTransitionFromOpenToHalfOpen;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public long getSlowCallDurationThresholdMs() {
            return slowCallDurationThresholdMs;
        }

        public void setSlowCallDurationThresholdMs(long slowCallDurationThresholdMs) {
            this.slowCallDurationThresholdMs = slowCallDurationThresholdMs;
        }

        public long getWaitDurationInOpenStateMs() {
            return waitDurationInOpenStateMs;
        }

        public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) {
            this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public boolean isAutomaticTransitionFromOpenToHalfOpen() {
            return automaticTransitionFromOpenToHalfOpen;
        }

        public void setAutomaticTransitionFromOpenToHalfOpen(boolean automaticTransitionFromOpenToHalfOpen) {
            this.automaticTransitionFromOpenToHalfOpen = automaticTransitionFromOpenToHalfOpen;
        }

        public void validate(String dependency) {
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException(dependency + ".failureRateThreshold must be in (0, 100]");
            }
            if (slowCallRateThreshold <= 0 || slowCallRateThreshold > 100) {
                throw new IllegalArgumentException(dependency + ".slowCallRateThreshold must be in (0, 100]");
            }
            if (slowCallDurationThresholdMs <= 0 || waitDurationInOpenStateMs <= 0) {
                throw new IllegalArgumentException(dependency + " timeout values must be positive");
            }
            if (permittedNumberOfCallsInHalfOpenState <= 0
                    || slidingWindowSize <= 0
                    || minimumNumberOfCalls <= 0
                    || minimumNumberOfCalls > slidingWindowSize) {
                throw new IllegalArgumentException(dependency + " window values are invalid");
            }
        }
    }
}
