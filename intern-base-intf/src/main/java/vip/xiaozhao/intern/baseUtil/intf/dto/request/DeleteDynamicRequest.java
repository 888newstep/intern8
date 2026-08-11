package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(name = "删除动态请求")
public class DeleteDynamicRequest {


    @Schema(description = "动态ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "动态ID不能为空")
    @Positive
    private Long dynamicId;



    public Long getDynamicId() {
        return dynamicId;
    }

    public void setDynamicId(Long dynamicId) {
        this.dynamicId = dynamicId;
    }
}
