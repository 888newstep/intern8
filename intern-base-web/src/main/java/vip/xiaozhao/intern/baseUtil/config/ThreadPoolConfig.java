package vip.xiaozhao.intern.baseUtil.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Business async thread pool configuration.
 * Replaces the default SimpleAsyncTaskExecutor with a bounded pool
 * to prevent unbounded thread creation under load.
 */
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Bean(name = "businessExecutor")
    public Executor businessExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("biz-async-");
        // CallerRunsPolicy: caller thread executes the task when pool is full,
        // providing natural back-pressure instead of rejecting.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();

        // Expose thread pool metrics to Micrometer/Prometheus
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "businessExecutor");

        logger.info("Business async thread pool initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }
}