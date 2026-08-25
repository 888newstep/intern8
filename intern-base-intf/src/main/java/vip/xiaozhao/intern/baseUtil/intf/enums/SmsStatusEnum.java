package vip.xiaozhao.intern.baseUtil.intf.enums;


public enum SmsStatusEnum {

    Sended(0, "短信已发送"),
    VerifySuccess(1, "验证成功"),
    VerifyFail(2, "验证失败");

    private final int id;
    private final String name;


    SmsStatusEnum(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public static SmsStatusEnum getById(int id) {
        for (SmsStatusEnum smsType : SmsStatusEnum.values()) {
            if (smsType.getId() == id)
                return smsType;
        }
        return null;
    }

    public int getId() {
        return id;
    }

    /**
     * Getter method for property <tt>name</tt>.
     *
     * @return property value of name
     */
    public String getName() {
        return name;
    }

}
