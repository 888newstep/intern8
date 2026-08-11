package vip.xiaozhao.intern.baseUtil.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.logging.ErrorLogRateLimiter;
import vip.xiaozhao.intern.baseUtil.logging.SensitiveDataSanitizer;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Logs request and service metadata only. Request/response bodies are intentionally
 * excluded; callers that need diagnostics must log a bounded sanitized projection.
 */
@Aspect
@Component
public class LogAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogAspect.class);
    private static final String EVENT_TYPE_KEY = "eventType";
    private static final String DURATION_MS_KEY = "durationMs";
    private static final String ERROR_CODE_KEY = "errorCode";

    private final ErrorLogRateLimiter errorLogRateLimiter;

    public LogAspect(ErrorLogRateLimiter errorLogRateLimiter) {
        this.errorLogRateLimiter = errorLogRateLimiter;
    }

    @Pointcut("execution(* vip.xiaozhao.intern.baseUtil.controller..*.*(..))")
    public void controllerPointcut() {
    }

    @Pointcut("execution(* vip.xiaozhao.intern.baseUtil.service..*.*(..))")
    public void servicePointcut() {
    }

    @Around("controllerPointcut()")
    public Object aroundController(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.nanoTime();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String handler = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        RequestMetadata request = currentRequest();

        MDC.put(EVENT_TYPE_KEY, "http.request");
        try {
            LOGGER.info("request.started method={} uri={} handler={}",
                    request.method(), request.uri(), handler);

            Object result = joinPoint.proceed();
            putResponseCode(result);
            putDuration(startedAt);
            LOGGER.info("request.completed method={} uri={} handler={} success={}",
                    request.method(), request.uri(), handler, responseSucceeded(result));
            return result;
        } catch (Throwable throwable) {
            putDuration(startedAt);
            String rateLimitKey = "http:" + handler + ":" + throwable.getClass().getName();
            if (errorLogRateLimiter.tryAcquire(rateLimitKey)) {
                LOGGER.error("request.failed method={} uri={} handler={} exceptionType={} message={}",
                        request.method(), request.uri(), handler,
                        throwable.getClass().getSimpleName(),
                        SensitiveDataSanitizer.sanitizeText(throwable.getMessage()), throwable);
            } else {
                LOGGER.debug("request.failed.sampled handler={} exceptionType={}",
                        handler, throwable.getClass().getSimpleName());
            }
            throw throwable;
        } finally {
            restoreMdc(previousMdc);
        }
    }

    @Around("servicePointcut()")
    public Object aroundService(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.nanoTime();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String operation = signature.getDeclaringType().getSimpleName() + "." + signature.getName();

        MDC.put(EVENT_TYPE_KEY, "service.call");
        try {
            LOGGER.debug("service.started operation={}", operation);
            Object result = joinPoint.proceed();
            putDuration(startedAt);
            LOGGER.debug("service.completed operation={}", operation);
            return result;
        } catch (Throwable throwable) {
            putDuration(startedAt);
            String rateLimitKey = "service:" + operation + ":" + throwable.getClass().getName();
            if (errorLogRateLimiter.tryAcquire(rateLimitKey)) {
                LOGGER.error("service.failed operation={} exceptionType={} message={}",
                        operation, throwable.getClass().getSimpleName(),
                        SensitiveDataSanitizer.sanitizeText(throwable.getMessage()), throwable);
            } else {
                LOGGER.debug("service.failed.sampled operation={} exceptionType={}",
                        operation, throwable.getClass().getSimpleName());
            }
            throw throwable;
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private RequestMetadata currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return new RequestMetadata("-", "-");
        }
        HttpServletRequest request = attributes.getRequest();
        return new RequestMetadata(
                valueOrDash(request.getMethod()),
                valueOrDash(request.getRequestURI())
        );
    }

    private void putResponseCode(Object result) {
        if (result instanceof ResponseDO response && response.getErrorCode() != null) {
            MDC.put(ERROR_CODE_KEY, String.valueOf(response.getErrorCode()));
        } else {
            MDC.remove(ERROR_CODE_KEY);
        }
    }

    private boolean responseSucceeded(Object result) {
        return !(result instanceof ResponseDO response) || response.isSuccess();
    }

    private void putDuration(long startedAt) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        MDC.put(DURATION_MS_KEY, String.valueOf(durationMs));
    }

    private void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc == null || previousMdc.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record RequestMetadata(String method, String uri) {
    }
}
