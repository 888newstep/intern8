package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.utils.RedisCommandClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Idempotency guard for HTTP operations that create or mutate business data.
 *
 * <p>The Redis hash stores the request fingerprint, processing state and the
 * final response. Lua scripts make claim, completion and failure transitions
 * atomic. Redis errors fail closed: the business operation is not executed
 * when the guard cannot establish ownership.</p>
 */
@Service
public class ApiIdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiIdempotencyService.class);

    private static final String KEY_PREFIX = "idempotency:api:";
    private static final String RESPONSE_FIELD = "response";

    private static final int FINAL_TTL_SECONDS = 24 * 60 * 60;
    private static final int PROCESSING_LEASE_SECONDS = 120;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_CACHED_RESPONSE_BYTES = 64 * 1024;

    private static final int ACQUIRED = 1;
    private static final int IN_PROGRESS = 0;
    private static final int FINGERPRINT_CONFLICT = -1;
    private static final int CACHED_RESPONSE = 2;

    /**
     * ARGV[1] fingerprint, ARGV[2] current epoch second,
     * ARGV[3] new lease deadline, ARGV[4] final record TTL.
     */
    private static final String ACQUIRE_SCRIPT =
            "local existingFingerprint = redis.call('hget', KEYS[1], 'fingerprint') " +
            "if not existingFingerprint then " +
            "  redis.call('hset', KEYS[1], 'fingerprint', ARGV[1], 'status', 'PROCESSING', 'leaseUntil', ARGV[3]) " +
            "  redis.call('expire', KEYS[1], ARGV[4]) " +
            "  return 1 " +
            "end " +
            "if existingFingerprint ~= ARGV[1] then return -1 end " +
            "local status = redis.call('hget', KEYS[1], 'status') " +
            "if status == 'DONE' then return 2 end " +
            "local leaseUntil = tonumber(redis.call('hget', KEYS[1], 'leaseUntil') or '0') " +
            "if status == 'PROCESSING' and leaseUntil > tonumber(ARGV[2]) then return 0 end " +
            "redis.call('hset', KEYS[1], 'status', 'PROCESSING', 'leaseUntil', ARGV[3]) " +
            "redis.call('hdel', KEYS[1], 'response', 'lastError') " +
            "redis.call('expire', KEYS[1], ARGV[4]) " +
            "return 1";

    /** ARGV[1] fingerprint, ARGV[2] serialized ResponseDO, ARGV[3] final TTL. */
    private static final String COMPLETE_SCRIPT =
            "if redis.call('hget', KEYS[1], 'fingerprint') ~= ARGV[1] then return 0 end " +
            "if redis.call('hget', KEYS[1], 'status') ~= 'PROCESSING' then return 0 end " +
            "redis.call('hset', KEYS[1], 'status', 'DONE', 'response', ARGV[2], 'leaseUntil', '0') " +
            "redis.call('hdel', KEYS[1], 'lastError') " +
            "redis.call('expire', KEYS[1], ARGV[3]) " +
            "return 1";

    /** ARGV[1] fingerprint, ARGV[2] bounded error summary, ARGV[3] final TTL. */
    private static final String FAIL_SCRIPT =
            "if redis.call('hget', KEYS[1], 'fingerprint') ~= ARGV[1] then return 0 end " +
            "if redis.call('hget', KEYS[1], 'status') ~= 'PROCESSING' then return 0 end " +
            "redis.call('hset', KEYS[1], 'status', 'FAILED', 'lastError', ARGV[2], 'leaseUntil', '0') " +
            "redis.call('hdel', KEYS[1], 'response') " +
            "redis.call('expire', KEYS[1], ARGV[3]) " +
            "return 1";

    private final RedisCommandClient redisCommandClient;
    private final ObjectMapper objectMapper;
    private final Counter hitCounter;
    private final Counter conflictCounter;
    private final Counter processingCounter;
    private final Counter unavailableCounter;
    private final Counter completedCounter;
    private final Counter executionFailureCounter;

    public ApiIdempotencyService(RedisCommandClient redisCommandClient,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.redisCommandClient = redisCommandClient;
        this.objectMapper = objectMapper;
        this.hitCounter = Counter.builder("api.idempotency.hit")
                .description("Requests served from a completed idempotency record")
                .register(meterRegistry);
        this.conflictCounter = Counter.builder("api.idempotency.conflict")
                .description("Requests rejected because the key was reused with another payload")
                .register(meterRegistry);
        this.processingCounter = Counter.builder("api.idempotency.in_progress")
                .description("Requests rejected because another request owns the key")
                .register(meterRegistry);
        this.unavailableCounter = Counter.builder("api.idempotency.unavailable")
                .description("Requests rejected because Redis idempotency storage is unavailable")
                .register(meterRegistry);
        this.completedCounter = Counter.builder("api.idempotency.completed")
                .description("Business operations whose final response was persisted")
                .register(meterRegistry);
        this.executionFailureCounter = Counter.builder("api.idempotency.execution_failure")
                .description("Business operations that threw after claiming an idempotency key")
                .register(meterRegistry);
    }

    /**
     * Executes an operation at most once for the same user, route, key and
     * request fingerprint during the final-record TTL.
     *
     * <p>A missing key bypasses the guard for backward compatibility. New
     * clients should always send {@code Idempotency-Key} for side-effecting
     * requests.</p>
     */
    public ResponseDO execute(Long userId,
                              String route,
                              String idempotencyKey,
                              Object request,
                              Supplier<ResponseDO> operation) {
        Objects.requireNonNull(operation, "operation must not be null");

        if (userId == null) {
            return ResponseDO.fail(401, "请先登录");
        }

        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey == null) {
            logger.debug("Idempotency key missing; bypassing guard, route={}, userId={}", route, userId);
            return operation.get();
        }
        if (normalizedKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH || containsControlCharacter(normalizedKey)) {
            return ResponseDO.fail(400, "Idempotency-Key 格式非法");
        }

        String fingerprint;
        try {
            fingerprint = fingerprint(userId, route, request);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to build idempotency fingerprint, route={}, userId={}", route, userId, e);
            return ResponseDO.fail(400, "请求参数无法生成幂等指纹");
        }

        String redisKey = KEY_PREFIX + sha256(userId + "|" + route + "|" + normalizedKey);
        Long acquireResult = acquire(redisKey, fingerprint);
        if (acquireResult == null) {
            unavailableCounter.increment();
            return unavailableResponse();
        }

        if (acquireResult == FINGERPRINT_CONFLICT) {
            conflictCounter.increment();
            return ResponseDO.fail(409, "同一个 Idempotency-Key 不能对应不同请求参数");
        }
        if (acquireResult == IN_PROGRESS) {
            processingCounter.increment();
            return ResponseDO.fail(409, "相同请求正在处理中，请稍后查询结果");
        }
        if (acquireResult == CACHED_RESPONSE) {
            ResponseDO cached = loadCachedResponse(redisKey);
            if (cached != null) {
                hitCounter.increment();
                return cached;
            }
            unavailableCounter.increment();
            logger.error("Idempotency record is DONE but response is missing, key={}", redisKey);
            return unavailableResponse();
        }
        if (acquireResult != ACQUIRED) {
            unavailableCounter.increment();
            logger.error("Unknown idempotency acquire result={}, key={}", acquireResult, redisKey);
            return unavailableResponse();
        }

        try {
            ResponseDO response = operation.get();
            if (response == null) {
                throw new IllegalStateException("Idempotent operation returned null response");
            }
            if (!complete(redisKey, fingerprint, response)) {
                logger.error("Business operation completed but idempotency response was not persisted, key={}", redisKey);
                unavailableCounter.increment();
            } else {
                completedCounter.increment();
            }
            return response;
        } catch (RuntimeException e) {
            executionFailureCounter.increment();
            markFailed(redisKey, fingerprint, e);
            throw e;
        }
    }

    private Long acquire(String redisKey, String fingerprint) {
        long now = Instant.now().getEpochSecond();
        try {
            Object result = redisCommandClient.eval(
                    ACQUIRE_SCRIPT,
                    List.of(redisKey),
                    List.of(fingerprint,
                            String.valueOf(now),
                            String.valueOf(now + PROCESSING_LEASE_SECONDS),
                            String.valueOf(FINAL_TTL_SECONDS)));
            return toLong(result);
        } catch (Exception e) {
            logger.error("Failed to acquire idempotency key={}", redisKey, e);
            return null;
        }
    }

    private boolean complete(String redisKey, String fingerprint, ResponseDO response) {
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            if (responseJson.getBytes(StandardCharsets.UTF_8).length > MAX_CACHED_RESPONSE_BYTES) {
                logger.warn("Idempotency response exceeds cache limit, key={}", redisKey);
                return false;
            }
            return isOne(redisCommandClient.eval(
                    COMPLETE_SCRIPT,
                    List.of(redisKey),
                    List.of(fingerprint, responseJson, String.valueOf(FINAL_TTL_SECONDS))));
        } catch (Exception e) {
            logger.error("Failed to persist idempotency response, key={}", redisKey, e);
            return false;
        }
    }

    private void markFailed(String redisKey, String fingerprint, RuntimeException exception) {
        try {
            redisCommandClient.eval(
                    FAIL_SCRIPT,
                    List.of(redisKey),
                    List.of(fingerprint, boundedError(exception), String.valueOf(FINAL_TTL_SECONDS)));
        } catch (Exception e) {
            logger.error("Failed to persist idempotency failure, key={}", redisKey, e);
        }
    }

    private ResponseDO loadCachedResponse(String redisKey) {
        try {
            String responseJson = redisCommandClient.hget(redisKey, RESPONSE_FIELD);
            if (responseJson == null || responseJson.isBlank()) {
                return null;
            }
            return objectMapper.readValue(responseJson, ResponseDO.class);
        } catch (Exception e) {
            logger.error("Failed to load idempotency response, key={}", redisKey, e);
            return null;
        }
    }

    private String fingerprint(Long userId, String route, Object request) throws JsonProcessingException {
        byte[] requestBytes = objectMapper.writeValueAsBytes(request);
        return sha256(userId + "|" + route + "|" + new String(requestBytes, StandardCharsets.UTF_8));
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String boundedError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private ResponseDO unavailableResponse() {
        return ResponseDO.fail(503, "幂等服务暂不可用，请稍后重试");
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isOne(Object value) {
        Long number = toLong(value);
        return number != null && number == 1L;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
