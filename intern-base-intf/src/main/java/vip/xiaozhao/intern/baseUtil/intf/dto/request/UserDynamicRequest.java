package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Schema(name = "用户动态列表请求")
public class UserDynamicRequest {


    @Schema(description = "游标位置，首次请求不传或传最大值")
    @Positive
    private Long cursor;

    @Schema(description = "每页数量，默认20")
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
