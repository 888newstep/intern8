package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class DeleteNotificationRequest {

    @NotNull
    @Positive
    private Long notificationId;

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }
}
