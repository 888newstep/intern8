package vip.xiaozhao.intern.baseUtil.intf.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiNotification;

import java.util.List;

@Schema(name = "通知列表响应")
public class NotificationListResponse {

    @Schema(description = "通知列表")
    private List<TuiNotification> notifications;

    @Schema(description = "下一页游标")
    private Long cursor;

    @Schema(description = "是否有更多数据")
    private Boolean hasMore;

    public NotificationListResponse(List<TuiNotification> notifications, Long cursor, Boolean hasMore) {
        this.notifications = notifications;
        this.cursor = cursor;
        this.hasMore = hasMore;
    }

    public List<TuiNotification> getNotifications() {
        return notifications;
    }

    public void setNotifications(List<TuiNotification> notifications) {
        this.notifications = notifications;
    }

    public Long getCursor() {
        return cursor;
    }

    public void setCursor(Long cursor) {
        this.cursor = cursor;
    }

    public Boolean getHasMore() {
        return hasMore;
    }

    public void setHasMore(Boolean hasMore) {
        this.hasMore = hasMore;
    }
}