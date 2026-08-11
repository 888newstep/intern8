package vip.xiaozhao.intern.baseUtil.mq.event;

import java.time.Instant;
import java.util.UUID;

public abstract class BaseMqEvent {

    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private Long occurredAt;

    protected BaseMqEvent() {
    }

    protected void initMetadata(String eventType, int eventVersion) {
        this.eventId = UUID.randomUUID().toString().replace("-", "");
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.occurredAt = Instant.now().toEpochMilli();
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(Integer eventVersion) {
        this.eventVersion = eventVersion;
    }

    public Long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Long occurredAt) {
        this.occurredAt = occurredAt;
    }
}
