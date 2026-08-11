package vip.xiaozhao.intern.baseUtil.mq.event;

public class ArchiveDynamicEvent extends BaseMqEvent {

    public static final String EVENT_TYPE = "dynamic.archive";
    public static final int EVENT_VERSION = 1;

    private Long dynamicId;

    public ArchiveDynamicEvent() {
    }

    public static ArchiveDynamicEvent create(Long dynamicId) {
        ArchiveDynamicEvent event = new ArchiveDynamicEvent();
        event.initMetadata(EVENT_TYPE, EVENT_VERSION);
        event.setDynamicId(dynamicId);
        return event;
    }

    public Long getDynamicId() {
        return dynamicId;
    }

    public void setDynamicId(Long dynamicId) {
        this.dynamicId = dynamicId;
    }
}
