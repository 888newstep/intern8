package vip.xiaozhao.intern.baseUtil.intf.utils.redis;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.intf.utils.JsonUtils;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;
import vip.xiaozhao.intern.baseUtil.utils.SpringContextHolder;

import java.util.List;

/**
 * 兼容旧的静态 Redis 工具，底层统一转发到 Spring 管理的 RedisUtil。
 */
@Component
@Slf4j
public class RedisUtils {

    public static final int EXRP_ONE_MINITE = 60;
    public static final int EXRP_ONE_HOUR = 60 * 60;
    public static final int EXRP_ONE_DAY = 60 * 60 * 24;
    public static final int EXRP_HALF_AN_DAY = 60 * 60 * 24;
    public static final int EXRP_ONE_MONTH = 60 * 60 * 24 * 30;

    private static RedisUtil redisUtil() {
        return SpringContextHolder.getBean(RedisUtil.class);
    }

    public static boolean set(String key, Object obj, int seconds) {
        try {
            redisUtil().set(key, JsonUtils.toJson(obj), seconds);
            return true;
        } catch (Exception e) {
            log.debug("insert redis object failed", e);
            return false;
        }
    }

    public static boolean set(String key, String value, int seconds) {
        try {
            redisUtil().set(key, value, seconds);
            return true;
        } catch (Exception e) {
            log.debug("insert redis string failed", e);
            return false;
        }
    }

    public static boolean remove(String key) {
        try {
            redisUtil().delete(key);
            return true;
        } catch (Exception e) {
            log.debug("remove redis key failed", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Class<T> clazz) {
        try {
            String value = redisUtil().get(key);
            if (StringUtils.isBlank(value)) {
                return null;
            }
            return (T) JsonUtils.fromJson(value, clazz);
        } catch (Exception e) {
            log.debug("get redis object failed", e);
            return null;
        }
    }

    public static <T> List<T> getList(String key, Class<T> clazz) {
        try {
            String value = redisUtil().get(key);
            if (StringUtils.isBlank(value)) {
                return null;
            }
            return JsonUtils.fromJsonList(value, clazz);
        } catch (Exception e) {
            log.debug("get redis list failed", e);
            return null;
        }
    }

    public static String get(String key) {
        try {
            return redisUtil().get(key);
        } catch (Exception e) {
            log.debug("get redis string failed", e);
            return null;
        }
    }
}
