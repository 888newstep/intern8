package vip.xiaozhao.intern.baseUtil.intf.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "关注数量响应")
public class FollowCountResponse {

    @Schema(description = "粉丝数")
    private Integer followers;

    @Schema(description = "关注数")
    private Integer following;

    public FollowCountResponse(Integer followers, Integer following) {
        this.followers = followers;
        this.following = following;
    }

    public Integer getFollowers() {
        return followers;
    }

    public void setFollowers(Integer followers) {
        this.followers = followers;
    }

    public Integer getFollowing() {
        return following;
    }

    public void setFollowing(Integer following) {
        this.following = following;
    }
}