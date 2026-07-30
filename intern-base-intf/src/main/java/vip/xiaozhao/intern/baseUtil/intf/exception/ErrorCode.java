package vip.xiaozhao.intern.baseUtil.intf.exception;

public enum ErrorCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    DYNAMIC_NOT_FOUND(1001, "动态不存在"),
    DYNAMIC_DELETE_FAILED(1002, "动态删除失败"),
    DYNAMIC_NOT_OWNER(1003, "无权操作该动态"),

    FOLLOW_SELF(2001, "不能关注自己"),
    FOLLOW_ALREADY(2002, "已关注该用户"),
    FOLLOW_NOT_FOUND(2003, "未关注该用户"),

    USER_NOT_FOUND(3001, "用户不存在"),

    NOTIFICATION_NOT_FOUND(4001, "通知不存在"),

    TOO_FREQUENT(429, "操作太频繁"),
    LOCK_ACQUIRE_FAILED(429, "系统繁忙，请稍后重试");

    private Integer code;
    private String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}