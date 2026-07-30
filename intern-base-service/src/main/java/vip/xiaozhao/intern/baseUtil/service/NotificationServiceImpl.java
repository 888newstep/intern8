package vip.xiaozhao.intern.baseUtil.service;

import com.google.gson.Gson;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Gson gson = new Gson();

    private final RedisUtil redisUtil;
    private final RabbitMQSender rabbitMQSender;

    public NotificationServiceImpl(RedisUtil redisUtil, RabbitMQSender rabbitMQSender) {
        this.redisUtil = redisUtil;
        this.rabbitMQSender = rabbitMQSender;
    }

    // 未读通知计数前缀
    private static final String UNREAD_COUNT_PREFIX = "notification:unread:count:";

    // 发送通知
    public void sendNotification(Long userId, Long senderId, Integer type, String content, String targetId) {
        // 构建通知信息
        Map<String, Object> notificationInfo = new HashMap<>();
        notificationInfo.put("userId", userId);
        notificationInfo.put("senderId", senderId);
        notificationInfo.put("type", type);
        notificationInfo.put("content", content);
        notificationInfo.put("targetId", targetId);

        // 发送到RabbitMQ队列进行异步处理
        rabbitMQSender.sendNotificationMessage(gson.toJson(notificationInfo));

        // 更新未读计数
        String key = UNREAD_COUNT_PREFIX + userId;
        redisUtil.incr(key);
    }

    // 获取未读计数
    public Long getUnreadCount(Long userId) {
        String key = UNREAD_COUNT_PREFIX + userId;
        return redisUtil.getCount(key);
    }

    // 清空未读计数
    public void clearUnreadCount(Long userId) {
        String key = UNREAD_COUNT_PREFIX + userId;
        redisUtil.delete(key);
    }
}