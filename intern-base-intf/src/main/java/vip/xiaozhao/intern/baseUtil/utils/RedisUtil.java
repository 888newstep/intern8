package vip.xiaozhao.intern.baseUtil.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Set;

@Component
public class RedisUtil {

    private static final Logger logger = LoggerFactory.getLogger(RedisUtil.class);

    private final JedisPool jedisPool;

    public RedisUtil(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // 设置值
    public void set(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, value);
        } catch (JedisException e) {
            logger.error("Redis set error, key: {}", key, e);
        }
    }

    // 设置值并指定过期时间
    public void set(String key, String value, int expireTime) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, expireTime, value);
        } catch (JedisException e) {
            logger.error("Redis setex error, key: {}", key, e);
        }
    }

    // 获取值
    public String get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (JedisException e) {
            logger.error("Redis get error, key: {}", key, e);
            return null;
        }
    }

    // 删除值
    public void delete(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        } catch (JedisException e) {
            logger.error("Redis del error, key: {}", key, e);
        }
    }

    // 增加计数
    public Long incr(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.incr(key);
        } catch (JedisException e) {
            logger.error("Redis incr error, key: {}", key, e);
            return null;
        }
    }

    // 减少计数
    public Long decr(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.decr(key);
        } catch (JedisException e) {
            logger.error("Redis decr error, key: {}", key, e);
            return null;
        }
    }

    // 获取计数
    public Long getCount(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (JedisException e) {
            logger.error("Redis getCount error, key: {}", key, e);
            return 0L;
        }
    }

    // 设置哈希值
    public void hset(String key, String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(key, field, value);
        } catch (JedisException e) {
            logger.error("Redis hset error, key: {}, field: {}", key, field, e);
        }
    }

    // 获取哈希值
    public String hget(String key, String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hget(key, field);
        } catch (JedisException e) {
            logger.error("Redis hget error, key: {}, field: {}", key, field, e);
            return null;
        }
    }

    // 获取所有哈希字段
    public Set<String> hkeys(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hkeys(key);
        } catch (JedisException e) {
            logger.error("Redis hkeys error, key: {}", key, e);
            return null;
        }
    }

    // SETNX（不存在则设置）
    public Long setnx(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.setnx(key, value);
        } catch (JedisException e) {
            logger.error("Redis setnx error, key: {}", key, e);
            return 0L;
        }
    }

    // 设置过期时间
    public void expire(String key, long seconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(key, (int) seconds);
        } catch (JedisException e) {
            logger.error("Redis expire error, key: {}", key, e);
        }
    }
}