package vip.xiaozhao.intern.baseUtil.mq.event;

public class NotificationEvent extends BaseMqEvent {

    public static final String EVENT_TYPE = "notification.created";
    public static final int EVENT_VERSION = 1;

    private Long userId;
    private Long senderId;
    private Integer notificationType;
    private String content;
    private String targetId;

    public NotificationEvent() {
    }

    public static NotificationEvent create(Long userId, Long senderId, Integer notificationType,
                                           String content, String targetId) {
        NotificationEvent event = new NotificationEvent();
        event.initMetadata(EVENT_TYPE, EVENT_VERSION);
        event.setUserId(userId);
        event.setSenderId(senderId);
        event.setNotificationType(notificationType);
        event.setContent(content);
        event.setTargetId(targetId);
        return event;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Integer getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(Integer notificationType) {
        this.notificationType = notificationType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }
}
