package vip.xiaozhao.intern.baseUtil.intf.service;

public interface NotificationService {

    void sendNotification(Long userId, Long senderId, Integer type, String content, String targetId);

    Long getUnreadCount(Long userId);

    void clearUnreadCount(Long userId);
}