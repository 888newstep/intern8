package vip.xiaozhao.intern.baseUtil.intf.enums;

public enum NotificationTypeEnum {

    LIKE(1, "点赞"),
    COMMENT(2, "评论"),
    SHARE(3, "分享"),
    FOLLOW(4, "关注"),
    SYSTEM(5, "系统通知");

    private Integer code;
    private String description;

    NotificationTypeEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static NotificationTypeEnum getByCode(Integer code) {
        for (NotificationTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    public static boolean isValid(Integer code) {
        return getByCode(code) != null;
    }
}