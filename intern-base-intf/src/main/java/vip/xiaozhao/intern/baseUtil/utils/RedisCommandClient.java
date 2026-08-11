package vip.xiaozhao.intern.baseUtil.utils;

import java.util.Set;

public interface RedisCommandClient {

    void set(String key, String value);

    void set(String key, String value, int expireTime);

    String get(String key);

    void delete(String key);

    Long incr(String key);

    Long decr(String key);

    void hset(String key, String field, String value);

    String hget(String key, String field);

    Set<String> hkeys(String key);

    Long setnx(String key, String value);

    void expire(String key, long seconds);

    /**
     * Execute a Lua script atomically.
     */
    Object eval(String luaScript, java.util.List<String> keys, java.util.List<String> args);
}
