package vip.xiaozhao.intern.baseUtil.intf.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class JsonUtils {

    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 将对象转换为JSON字符串
     */
    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert object to JSON", e);
            return null;
        }
    }

    /**
     * 将JSON字符串转换为指定类型的对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse JSON to object", e);
            return null;
        }
    }

    /**
     * 将JSON字符串转换为指定类型的List
     */
    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        try {
            JavaType type = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse JSON to list", e);
            return null;
        }
    }
}