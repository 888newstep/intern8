package vip.xiaozhao.intern.baseUtil.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RedisUtil {

    private static final Logger logger = LoggerFactory.getLogger(RedisUtil.class);

    private final RedisCommandClient redisCommandClient;

    public RedisUtil(RedisCommandClient redisCommandClient) {
        this.redisCommandClient = redisCommandClient;
    }

    public void set(String key, String value) {
        try {
            redisCommandClient.set(key, value);
        } catch (Exception e) {
            logger.error("Redis set error, key: {}", key, e);
        }
    }

    public void set(String key, String value, int expireTime) {
        try {
            redisCommandClient.set(key, value, expireTime);
        } catch (Exception e) {
            logger.error("Redis setex error, key: {}", key, e);
        }
    }

    /**
     * 严格写入接口交给上层熔断策略处理，不在这里吞掉连接异常。
     */
    public void setStrict(String key, String value, int expireTime) {
        redisCommandClient.set(key, value, expireTime);
    }

    public String get(String key) {
        try {
            return redisCommandClient.get(key);
        } catch (Exception e) {
            logger.error("Redis get error, key: {}", key, e);
            return null;
        }
    }

    /**
     * 严格读取接口供缓存服务记录 Redis 下游失败。
     */
    public String getStrict(String key) {
        return redisCommandClient.get(key);
    }

    public void delete(String key) {
        try {
            redisCommandClient.delete(key);
        } catch (Exception e) {
            logger.error("Redis del error, key: {}", key, e);
        }
    }

    /**
     * Execute delete without swallowing the downstream exception.
     * Callers that own a circuit breaker must be able to record Redis failures.
     */
    public void deleteStrict(String key) {
        redisCommandClient.delete(key);
    }

    public Long incr(String key) {
        try {
            return redisCommandClient.incr(key);
        } catch (Exception e) {
            logger.error("Redis incr error, key: {}", key, e);
            return null;
        }
    }

    public Long decr(String key) {
        try {
            return redisCommandClient.decr(key);
        } catch (Exception e) {
            logger.error("Redis decr error, key: {}", key, e);
            return null;
        }
    }

    public Long getCount(String key) {
        try {
            String value = redisCommandClient.get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            logger.error("Redis getCount error, key: {}", key, e);
            return 0L;
        }
    }

    public void hset(String key, String field, String value) {
        try {
            redisCommandClient.hset(key, field, value);
        } catch (Exception e) {
            logger.error("Redis hset error, key: {}, field: {}", key, field, e);
        }
    }

    public String hget(String key, String field) {
        try {
            return redisCommandClient.hget(key, field);
        } catch (Exception e) {
            logger.error("Redis hget error, key: {}, field: {}", key, field, e);
            return null;
        }
    }

    public Set<String> hkeys(String key) {
        try {
            return redisCommandClient.hkeys(key);
        } catch (Exception e) {
            logger.error("Redis hkeys error, key: {}", key, e);
            return null;
        }
    }

    public Long setnx(String key, String value) {
        try {
            return redisCommandClient.setnx(key, value);
        } catch (Exception e) {
            logger.error("Redis setnx error, key: {}", key, e);
            return 0L;
        }
    }

    public void expire(String key, long seconds) {
        try {
            redisCommandClient.expire(key, seconds);
        } catch (Exception e) {
            logger.error("Redis expire error, key: {}", key, e);
        }
    }

    /**
     * Execute a Lua script atomically via RedisCommandClient.
     */
    public Object eval(String luaScript, java.util.List<String> keys, java.util.List<String> args) {
        try {
            return redisCommandClient.eval(luaScript, keys, args);
        } catch (Exception e) {
            logger.error("Redis eval error, script: {}", luaScript, e);
            return null;
        }
    }

    /**
     * Execute a Lua script without swallowing the exception.
     * Cache loading needs this distinction: a return value of 0 means lock contention,
     * while an exception means Redis is unavailable and the request should fail open to DB.
     */
    public Object evalStrict(String luaScript, java.util.List<String> keys, java.util.List<String> args) {
        return redisCommandClient.eval(luaScript, keys, args);
    }
}
