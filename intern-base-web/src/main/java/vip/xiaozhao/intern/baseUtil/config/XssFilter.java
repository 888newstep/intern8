package vip.xiaozhao.intern.baseUtil.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * XSS protection filter.
 * Wraps the request to sanitize all parameter values and headers
 * before they reach the controller layer.
 *
 * Reference: OWASP XSS Prevention Cheat Sheet
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class XssFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(new XssHttpServletRequestWrapper(request), response);
    }

    /**
     * Wraps HttpServletRequest to sanitize parameter values.
     */
    private static class XssHttpServletRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {

        private static final Safelist BASIC_SAFE_LIST = Safelist.none();

        public XssHttpServletRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            String value = super.getParameter(name);
            return value != null ? clean(value) : null;
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null) {
                return null;
            }
            String[] sanitized = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                sanitized[i] = values[i] != null ? clean(values[i]) : null;
            }
            return sanitized;
        }

        @Override
        public String getHeader(String name) {
            String value = super.getHeader(name);
            return value != null ? clean(value) : null;
        }

        /**
         * Strip dangerous HTML tags and attributes while preserving basic formatting.
         */
        private String clean(String value) {
            return Jsoup.clean(value, BASIC_SAFE_LIST);
        }
    }
}
