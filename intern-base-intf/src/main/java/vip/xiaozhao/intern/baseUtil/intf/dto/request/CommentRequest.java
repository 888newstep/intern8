package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import vip.xiaozhao.intern.baseUtil.intf.validation.XssSafe;
import jakarta.validation.constraints.Size;

@Schema(name = "评论请求")
public class CommentRequest {


    @Schema(description = "动态ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "动态ID不能为空")
    @Positive
    private Long dynamicId;


    @Schema(description = "评论内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容不能超过500字符")
    @NotBlank
    @XssSafe
    private String content;



    public Long getDynamicId() {
        return dynamicId;
    }

    public void setDynamicId(Long dynamicId) {
        this.dynamicId = dynamicId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
