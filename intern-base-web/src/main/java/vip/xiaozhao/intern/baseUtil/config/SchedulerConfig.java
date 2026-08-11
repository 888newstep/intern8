package vip.xiaozhao.intern.baseUtil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduled task thread pool configuration.
 * The default scheduler uses a single thread, which causes tasks to block each other.
 * This configures a dedicated pool with at least 2 threads.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerConfig.class);

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // Set error handler to prevent one task's exception from killing the scheduler
        scheduler.setErrorHandler(t ->
                logger.error("Scheduled task threw exception", t));
        scheduler.initialize();

        logger.info("Task scheduler initialized: poolSize={}", scheduler.getPoolSize());
        return scheduler;
    }
}