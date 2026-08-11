package vip.xiaozhao.intern.baseUtil.intf.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import vip.xiaozhao.intern.baseUtil.intf.validation.XssSafe;

public class SystemNotificationRequest {

    @NotNull
    @Positive
    private Long targetUserId;

    @NotNull
    @Size(max = 500)
    @NotBlank
    @XssSafe
    private String content;

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
