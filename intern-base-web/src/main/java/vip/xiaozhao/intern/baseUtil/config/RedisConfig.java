package vip.xiaozhao.intern.baseUtil.config;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.xiaozhao.intern.baseUtil.utils.RedisCommandClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Configuration
public class RedisConfig {

    @Bean
    public RedisCommandClient redisCommandClient(RedissonClient redissonClient) {
        return new RedissonRedisCommandClient(redissonClient);
    }

    private static final class RedissonRedisCommandClient implements RedisCommandClient {

        private final RedissonClient redissonClient;

        private RedissonRedisCommandClient(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
        }

        @Override
        public void set(String key, String value) {
            bucket(key).set(value);
        }

        @Override
        public void set(String key, String value, int expireTime) {
            bucket(key).set(value, Duration.ofSeconds(expireTime));
        }

        @Override
        public String get(String key) {
            Object value = bucket(key).get();
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public void delete(String key) {
            redissonClient.getKeys().delete(key);
        }

        @Override
        public Long incr(String key) {
            return atomicLong(key).incrementAndGet();
        }

        @Override
        public Long decr(String key) {
            return atomicLong(key).decrementAndGet();
        }

        @Override
        public void hset(String key, String field, String value) {
            map(key).put(field, value);
        }

        @Override
        public String hget(String key, String field) {
            Object value = map(key).get(field);
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public Set<String> hkeys(String key) {
            return map(key).keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
        }

        @Override
        public Long setnx(String key, String value) {
            return bucket(key).setIfAbsent(value) ? 1L : 0L;
        }

        @Override
        public void expire(String key, long seconds) {
            redissonClient.getKeys().expire(key, seconds, TimeUnit.SECONDS);
        }

        @Override
        public Object eval(String luaScript, List<String> keys, List<String> args) {
            List<Object> keysAsObjects = new ArrayList<>(keys);
            return redissonClient.getScript(StringCodec.INSTANCE)
                    .eval(RScript.Mode.READ_WRITE, luaScript,
                            RScript.ReturnType.INTEGER, keysAsObjects, args.toArray());
        }

        private RBucket<String> bucket(String key) {
            return redissonClient.getBucket(key, StringCodec.INSTANCE);
        }

        private RAtomicLong atomicLong(String key) {
            return redissonClient.getAtomicLong(key);
        }

        private RMap<String, String> map(String key) {
            // Lua scripts also use StringCodec; hashes must use the same wire encoding.
            return redissonClient.getMap(key, StringCodec.INSTANCE);
        }
    }
}
