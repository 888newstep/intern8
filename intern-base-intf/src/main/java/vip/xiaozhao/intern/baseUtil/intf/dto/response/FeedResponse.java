package vip.xiaozhao.intern.baseUtil.intf.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;

import java.util.List;

@Schema(name = "信息流响应")
public class FeedResponse {

    @Schema(description = "动态列表")
    private List<TuiDynamic> dynamics;

    @Schema(description = "下一页游标")
    private Long cursor;

    @Schema(description = "是否有更多数据")
    private Boolean hasMore;

    public FeedResponse(List<TuiDynamic> dynamics, Long cursor, Boolean hasMore) {
        this.dynamics = dynamics;
        this.cursor = cursor;
        this.hasMore = hasMore;
    }

    public List<TuiDynamic> getDynamics() {
        return dynamics;
    }

    public void setDynamics(List<TuiDynamic> dynamics) {
        this.dynamics = dynamics;
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