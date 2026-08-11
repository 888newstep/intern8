package vip.xiaozhao.intern.baseUtil.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean(name = "dynamicLocalCache")
    public Cache<Object, Object> dynamicLocalCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10000)
                .recordStats()
                .build();
    }

    @Bean
    public CacheManager caffeineCacheManager(
            @Qualifier("dynamicLocalCache") Cache<Object, Object> dynamicLocalCache) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(
                "dynamicDetail",
                dynamicLocalCache);
        return cacheManager;
    }
}
