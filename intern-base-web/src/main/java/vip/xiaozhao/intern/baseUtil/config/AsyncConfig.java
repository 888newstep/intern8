package vip.xiaozhao.intern.baseUtil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import vip.xiaozhao.intern.baseUtil.logging.SensitiveDataSanitizer;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);
    private final Executor businessExecutor;

    public AsyncConfig(@Qualifier("businessExecutor") Executor businessExecutor) {
        this.businessExecutor = businessExecutor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return businessExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> logger.error(
                "async.failed method={} params={}",
                method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                SensitiveDataSanitizer.sanitizeForLog(params, 1024), throwable);
    }
}
