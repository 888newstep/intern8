package vip.xiaozhao.intern.baseUtil.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void replacesInvalidExternalRequestIdAndReturnsSafeHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Request-Id", "x".repeat(129));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            String requestId = MDC.get("requestId");
            assertNotNull(requestId);
            assertTrue(requestId.matches("[A-Za-z0-9._:-]{1,128}"));
        };

        new MdcFilter().doFilter(request, response, chain);

        assertNotNull(response.getHeader("X-Request-Id"));
        assertTrue(response.getHeader("X-Request-Id").matches("[A-Za-z0-9._:-]{1,128}"));
    }

    @Test
    void disabledRequestContextDoesNotTouchMdcOrResponseHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
        };

        new MdcFilter(false).doFilter(request, response, chain);

        assertTrue(response.getHeader("X-Request-Id") == null);
    }
}
