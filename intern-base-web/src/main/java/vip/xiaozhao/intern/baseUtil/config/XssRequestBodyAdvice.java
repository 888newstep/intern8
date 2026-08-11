package vip.xiaozhao.intern.baseUtil.config;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import vip.xiaozhao.intern.baseUtil.intf.validation.XssSafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Sanitizes explicitly annotated text fields in JSON request bodies.
 *
 * URL and identifier fields are intentionally not sanitized here. They are
 * validated by their own constraints, while output encoding remains the final
 * XSS defense at the rendering boundary.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class XssRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(XssRequestBodyAdvice.class);
    private static final Safelist TEXT_SAFE_LIST = Safelist.none();

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage,
                                MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        sanitizeObject(body, Collections.newSetFromMap(new IdentityHashMap<>()));
        return body;
    }

    private void sanitizeObject(Object target, Set<Object> visited) {
        if (target == null || isSimpleType(target.getClass()) || !visited.add(target)) {
            return;
        }

        if (target instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                sanitizeObject(item, visited);
            }
            return;
        }

        if (target instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                sanitizeObject(value, visited);
            }
            return;
        }

        Class<?> currentType = target.getClass();
        while (currentType != null && currentType != Object.class) {
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                ReflectionUtils.makeAccessible(field);
                try {
                    Object value = field.get(target);
                    if (field.isAnnotationPresent(XssSafe.class) && value instanceof String text) {
                        field.set(target, Jsoup.clean(text, TEXT_SAFE_LIST));
                    } else {
                        sanitizeObject(value, visited);
                    }
                } catch (IllegalAccessException ex) {
                    logger.error("Failed to sanitize request field: {}.{}",
                            currentType.getName(), field.getName(), ex);
                    throw new IllegalArgumentException("Request body sanitization failed", ex);
                }
            }
            currentType = currentType.getSuperclass();
        }
    }

    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || String.class == type
                || type.getName().startsWith("java.");
    }
}
