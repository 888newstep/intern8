package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(name = "关注请求")
public class FollowRequest {


    @Schema(description = "被关注用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "被关注用户ID不能为空")
    @Positive
    private Long followUserId;



    public Long getFollowUserId() {
        return followUserId;
    }

    public void setFollowUserId(Long followUserId) {
        this.followUserId = followUserId;
    }
}
