package vip.xiaozhao.intern.baseUtil.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedissonConfig.class);

    /** Redis 接入模式：local single / prod sentinel / scale-out cluster */
    @Value("${redis.mode:single}")
    private String redisMode;

    @Value("${redis.ip:127.0.0.1}")
    private String redisIp;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Value("${redis.auth:}")
    private String redisAuth;

    @Value("${redis.sentinel.master:}")
    private String sentinelMaster;

    @Value("${redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Value("${redis.cluster.nodes:}")
    private String clusterNodes;

    @Value("${redis.connect-timeout-ms:${redis.timeOut:10000}}")
    private int connectTimeout;

    @Value("${redis.command-timeout-ms:${redis.timeOut:10000}}")
    private int commandTimeout;

    @Bean
    public RedissonClient redissonClient() {
        validateTimeouts();
        Config config = new Config();

        switch (redisMode.toLowerCase()) {
            case "sentinel":
                configureSentinel(config);
                break;
            case "cluster":
                configureCluster(config);
                break;
            default:
                configureSingle(config);
                break;
        }

        config.setNettyThreads(16);
        RedissonClient client = Redisson.create(config);
        logger.info("RedissonClient initialized, mode: {}", redisMode);
        return client;
    }

    private void configureSingle(Config config) {
        SingleServerConfig singleConfig = config.useSingleServer()
                .setAddress("redis://" + redisIp + ":" + redisPort)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(16)
                .setConnectTimeout(connectTimeout)
                .setTimeout(commandTimeout);
        if (redisAuth != null && !redisAuth.isEmpty()) {
            singleConfig.setPassword(redisAuth);
        }
    }

    private void configureSentinel(Config config) {
        SentinelServersConfig sentinelConfig = config.useSentinelServers()
                .setMasterName(sentinelMaster)
                .setDatabase(0)
                .setMasterConnectionPoolSize(64)
                .setMasterConnectionMinimumIdleSize(16)
                .setSlaveConnectionPoolSize(64)
                .setSlaveConnectionMinimumIdleSize(16)
                .setConnectTimeout(connectTimeout)
                .setTimeout(commandTimeout)
                .setReadMode(ReadMode.SLAVE);

        for (String node : sentinelNodes.split(",")) {
            sentinelConfig.addSentinelAddress("redis://" + node.trim());
        }

        if (redisAuth != null && !redisAuth.isEmpty()) {
            sentinelConfig.setPassword(redisAuth);
        }
        logger.info("Redis Sentinel configured: master={}, nodes={}", sentinelMaster, sentinelNodes);
    }

    private void configureCluster(Config config) {
        ClusterServersConfig clusterConfig = config.useClusterServers()
                .setScanInterval(2000)
                .setMasterConnectionPoolSize(64)
                .setMasterConnectionMinimumIdleSize(16)
                .setSlaveConnectionPoolSize(64)
                .setSlaveConnectionMinimumIdleSize(16)
                .setConnectTimeout(connectTimeout)
                .setTimeout(commandTimeout)
                .setReadMode(ReadMode.SLAVE);

        for (String node : clusterNodes.split(",")) {
            clusterConfig.addNodeAddress("redis://" + node.trim());
        }

        if (redisAuth != null && !redisAuth.isEmpty()) {
            clusterConfig.setPassword(redisAuth);
        }
        logger.info("Redis Cluster configured: nodes={}", clusterNodes);
    }

    private void validateTimeouts() {
        if (connectTimeout <= 0 || commandTimeout <= 0) {
            throw new IllegalArgumentException("Redis connect and command timeouts must be positive");
        }
    }
}
