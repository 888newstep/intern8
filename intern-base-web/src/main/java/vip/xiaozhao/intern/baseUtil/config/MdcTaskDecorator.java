package vip.xiaozhao.intern.baseUtil.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates MDC context from the calling thread to async/scheduled threads.
 * Without this, log lines in @Async methods lose requestId and userId.
 *
 * Usage: configure on ThreadPoolTaskExecutor via setTaskDecorator(new MdcTaskDecorator())
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture MDC context from the calling thread
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (contextMap == null || contextMap.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                if (previousContext == null || previousContext.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContext);
                }
            }
        };
    }
}
