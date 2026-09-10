package vip.xiaozhao.intern.baseUtil.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcFilterTest {

    @BeforeEach
    void resetMdc() {
        MDC.clear();
    }

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
        MDC.put("existing", "preserved");
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertEquals("preserved", MDC.get("existing"));
            assertNull(MDC.get("requestId"));
        };

        new MdcFilter(false).doFilter(request, response, chain);

        assertEquals("preserved", MDC.get("existing"));
        assertNull(response.getHeader("X-Request-Id"));
    }
}
