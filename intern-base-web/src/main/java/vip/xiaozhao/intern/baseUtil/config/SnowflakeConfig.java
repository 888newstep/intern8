package vip.xiaozhao.intern.baseUtil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

/**
 * 雪花算法ID生成器配置
 * workerId可通过配置文件设置，生产环境建议通过Redis自增分配
 */
@Configuration
public class SnowflakeConfig {

    private static final Logger logger = LoggerFactory.getLogger(SnowflakeConfig.class);

    @Value("${snowflake.worker-id:0}")
    private long workerId;

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        logger.info("Initializing SnowflakeIdGenerator with workerId: {}", workerId);
        return new SnowflakeIdGenerator(workerId);
    }
}