package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "动态发布请求")
public class DynamicPublishRequest {

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Schema(description = "动态内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "动态内容不能为空")
    @Size(max = 5000, message = "动态内容不能超过5000字符")
    private String content;

    @Schema(description = "图片URL，多个用逗号分隔")
    @Size(max = 2000, message = "图片URL不能超过2000字符")
    private String images;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }
}