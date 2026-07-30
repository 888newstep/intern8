package vip.xiaozhao.intern.baseUtil.controller.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求日志拦截器
 * 认证由Spring Security + JWT处理
 */
@Component
@Slf4j
public class PrehandleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        log.info("Request: {} {} from {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        return true;
    }
}