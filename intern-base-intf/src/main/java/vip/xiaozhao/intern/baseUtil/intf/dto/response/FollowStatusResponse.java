package vip.xiaozhao.intern.baseUtil.intf.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "关注状态响应")
public class FollowStatusResponse {

    @Schema(description = "是否已关注")
    private Boolean isFollowing;

    public FollowStatusResponse(Boolean isFollowing) {
        this.isFollowing = isFollowing;
    }

    public Boolean getIsFollowing() {
        return isFollowing;
    }

    public void setIsFollowing(Boolean isFollowing) {
        this.isFollowing = isFollowing;
    }
}