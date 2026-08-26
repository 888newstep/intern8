package vip.xiaozhao.intern.baseUtil.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.xiaozhao.intern.baseUtil.logging.SensitiveDataSanitizer;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Injects request context into MDC (Mapped Diagnostic Context) for log tracing.
 * Every log line within the same request will include requestId, userId, and requestUri,
 * enabling end-to-end request tracing across service boundaries and async threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MdcFilter extends OncePerRequestFilter {

    private final boolean requestContextEnabled;

    private static final String REQUEST_ID_KEY = "requestId";
    private static final String USER_ID_KEY = "userId";
    private static final String CLIENT_IP_KEY = "clientIp";
    private static final String REQUEST_URI_KEY = "requestUri";
    private static final String REQUEST_METHOD_KEY = "httpMethod";
    private static final String EVENT_TYPE_KEY = "eventType";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public MdcFilter() {
        this(true);
    }

    public MdcFilter(@Value("${logging.request-context.enabled:true}") boolean requestContextEnabled) {
        this.requestContextEnabled = requestContextEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requestContextEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
            MDC.put(REQUEST_ID_KEY, requestId);
            MDC.put(REQUEST_URI_KEY, SensitiveDataSanitizer.truncate(request.getRequestURI(), 512));
            MDC.put(REQUEST_METHOD_KEY, request.getMethod());
            MDC.put(EVENT_TYPE_KEY, "http.request");
            if (request.getRemoteAddr() != null) {
                MDC.put(CLIENT_IP_KEY, SensitiveDataSanitizer.truncate(request.getRemoteAddr(), 128));
            }

            // Add response header so clients can correlate
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);

        } finally {
            if (previousMdc == null || previousMdc.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
