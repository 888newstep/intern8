package vip.xiaozhao.intern.baseUtil.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.ReadMode;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 分布式组件配置
 * 支持三种部署模式：单机 / 哨兵 / 集群
 * 提供：分布式锁、分布式限流器、分布式信号量等高级能力
 */
@Configuration
public class RedissonConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedissonConfig.class);

    /** Redis部署模式: single / sentinel / cluster */
    @Value("${redis.mode:single}")
    private String redisMode;

    @Value("${redis.ip:127.0.0.1}")
    private String redisIp;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Value("${redis.auth:}")
    private String redisAuth;

    /** 哨兵模式配置 */
    @Value("${redis.sentinel.master:}")
    private String sentinelMaster;

    @Value("${redis.sentinel.nodes:}")
    private String sentinelNodes;

    /** 集群模式配置 */
    @Value("${redis.cluster.nodes:}")
    private String clusterNodes;

    @Value("${redis.timeOut:10000}")
    private int timeout;

    @Bean
    public RedissonClient redissonClient() {
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

        // 全局连接池配置
        config.setNettyThreads(16);

        RedissonClient client = Redisson.create(config);
        logger.info("RedissonClient initialized, mode: {}", redisMode);
        return client;
    }

    /**
     * 单机模式（开发/测试环境）
     */
    private void configureSingle(Config config) {
        SingleServerConfig singleConfig = config.useSingleServer()
                .setAddress("redis://" + redisIp + ":" + redisPort)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(16)
                .setConnectTimeout(timeout)
                .setTimeout(timeout);
        if (redisAuth != null && !redisAuth.isEmpty()) {
            singleConfig.setPassword(redisAuth);
        }
    }

    /**
     * 哨兵模式（生产环境高可用）
     * 解决：单机宕机时自动切换，缓存雪崩自动恢复
     * 要求：至少3个哨兵节点 + 1主2从
     */
    private void configureSentinel(Config config) {
        SentinelServersConfig sentinelConfig = config.useSentinelServers()
                .setMasterName(sentinelMaster)
                .setDatabase(0)
                .setMasterConnectionPoolSize(64)
                .setMasterConnectionMinimumIdleSize(16)
                .setSlaveConnectionPoolSize(64)
                .setSlaveConnectionMinimumIdleSize(16)
                .setConnectTimeout(timeout)
                .setTimeout(timeout)
                // 哨兵模式特有：读写分离，从节点优先读
                        .setReadMode(ReadMode.SLAVE);

        // 添加所有哨兵节点
        for (String node : sentinelNodes.split(",")) {
            sentinelConfig.addSentinelAddress("redis://" + node.trim());
        }

        if (redisAuth != null && !redisAuth.isEmpty()) {
            sentinelConfig.setPassword(redisAuth);
        }
        logger.info("Redis Sentinel configured: master={}, nodes={}", sentinelMaster, sentinelNodes);
    }

    /**
     * 集群模式（大规模生产环境）
     * 解决：数据分片、水平扩展、自动故障转移
     * 要求：至少3主3从
     */
    private void configureCluster(Config config) {
        ClusterServersConfig clusterConfig = config.useClusterServers()
                .setScanInterval(2000) // 集群状态扫描间隔（ms）
                .setMasterConnectionPoolSize(64)
                .setMasterConnectionMinimumIdleSize(16)
                .setSlaveConnectionPoolSize(64)
                .setSlaveConnectionMinimumIdleSize(16)
                .setConnectTimeout(timeout)
                .setTimeout(timeout)
                // 集群模式特有：读写分离
                .setReadMode(ReadMode.SLAVE);

        // 添加所有集群节点
        for (String node : clusterNodes.split(",")) {
            clusterConfig.addNodeAddress("redis://" + node.trim());
        }

        if (redisAuth != null && !redisAuth.isEmpty()) {
            clusterConfig.setPassword(redisAuth);
        }
        logger.info("Redis Cluster configured: nodes={}", clusterNodes);
    }
}