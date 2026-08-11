package vip.xiaozhao.intern.baseUtil.service;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String UNREAD_COUNT_PREFIX = "notification:unread:count:";

    private final RedisUtil redisUtil;
    private final RabbitMQSender rabbitMQSender;

    public NotificationServiceImpl(RedisUtil redisUtil, RabbitMQSender rabbitMQSender) {
        this.redisUtil = redisUtil;
        this.rabbitMQSender = rabbitMQSender;
    }

    @Override
    @Async("businessExecutor")
    public void sendNotification(Long userId, Long senderId, Integer type, String content, String targetId) {
        NotificationEvent event = NotificationEvent.create(userId, senderId, type, content, targetId);
        rabbitMQSender.sendNotificationMessage(event);
    }

    @Override
    public Long getUnreadCount(Long userId) {
        String key = UNREAD_COUNT_PREFIX + userId;
        return redisUtil.getCount(key);
    }

    @Override
    public void clearUnreadCount(Long userId) {
        String key = UNREAD_COUNT_PREFIX + userId;
        redisUtil.delete(key);
    }
}
