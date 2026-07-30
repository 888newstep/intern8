package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
public class RedisConfig {

    @Value("${redis.ip}")
    private String redisIp;

    @Value("${redis.port}")
    private int redisPort;

    @Value("${redis.auth}")
    private String redisAuth;

    @Value("${redis.maxActive}")
    private int maxActive;

    @Value("${redis.maxIdle}")
    private int maxIdle;

    @Value("${redis.maxWaitTime}")
    private int maxWaitTime;

    @Value("${redis.timeOut}")
    private int timeOut;

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxActive);
        config.setMaxIdle(maxIdle);
        config.setMaxWait(java.time.Duration.ofMillis(maxWaitTime));
        config.setTestOnBorrow(true);
        return new JedisPool(config, redisIp, redisPort, timeOut, redisAuth);
    }
}