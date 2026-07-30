package vip.xiaozhao.intern.baseUtil.aspect;

import com.google.gson.Gson;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
public class LogAspect {

    private static final Logger logger = LoggerFactory.getLogger(LogAspect.class);
    private static final Gson gson = new Gson();

    @Pointcut("execution(* vip.xiaozhao.intern.baseUtil.controller..*.*(..))")
    public void controllerPointcut() {}

    @Pointcut("execution(* vip.xiaozhao.intern.baseUtil.service..*.*(..))")
    public void servicePointcut() {}

    @Around("controllerPointcut()")
    public Object aroundController(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String requestUri = null;
        String requestMethod = null;
        String clientIp = null;

        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestUri = request.getRequestURI();
            requestMethod = request.getMethod();
            clientIp = getClientIp(request);
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = method.getName();
        Object[] args = joinPoint.getArgs();

        Map<String, Object> logContext = new HashMap<>();
        logContext.put("requestUri", requestUri);
        logContext.put("requestMethod", requestMethod);
        logContext.put("clientIp", clientIp);
        logContext.put("className", className);
        logContext.put("methodName", methodName);

        try {
            logger.info("Request Start - {} {} | Class: {} | Method: {} | Args: {}",
                    requestMethod, requestUri, className, methodName, gson.toJson(args));

            Object result = joinPoint.proceed();

            long cost = System.currentTimeMillis() - startTime;
            logContext.put("cost", cost);
            logContext.put("success", true);

            logger.info("Request End - {} {} | Cost: {}ms | Result: {}",
                    requestMethod, requestUri, cost, gson.toJson(result));

            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - startTime;
            logContext.put("cost", cost);
            logContext.put("success", false);
            logContext.put("error", e.getMessage());

            logger.error("Request Error - {} {} | Cost: {}ms | Error: {}",
                    requestMethod, requestUri, cost, e.getMessage(), e);

            throw e;
        }
    }

    @Around("servicePointcut()")
    public Object aroundService(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = signature.getName();
        Object[] args = joinPoint.getArgs();

        try {
            logger.debug("Service Start - Class: {} | Method: {} | Args: {}",
                    className, methodName, gson.toJson(args));

            Object result = joinPoint.proceed();

            long cost = System.currentTimeMillis() - startTime;
            logger.debug("Service End - Class: {} | Method: {} | Cost: {}ms",
                    className, methodName, cost);

            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - startTime;
            logger.error("Service Error - Class: {} | Method: {} | Cost: {}ms | Error: {}",
                    className, methodName, cost, e.getMessage(), e);
            throw e;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}