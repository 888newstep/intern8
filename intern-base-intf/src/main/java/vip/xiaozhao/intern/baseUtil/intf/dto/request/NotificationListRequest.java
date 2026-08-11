package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public class NotificationListRequest {

    @Positive
    private Long cursor;

    @Min(1)
    @Max(100)
    private Integer limit;

    public Long getCursor() {
        return cursor;
    }

    public void setCursor(Long cursor) {
        this.cursor = cursor;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
