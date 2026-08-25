package vip.xiaozhao.intern.baseUtil.intf.enums;


public enum SmsTypeEnum {

    REGISTER(1, "注册","111"),
    RESET_PASSWORD(2, "修改密码", "111"),
    RESET_MOBILE(3, "修改手机","111"),
    RESET_TO_NEW_MOBILE(4, "向新手机发送短信","111"),
    Login(10, "手机验证码登录","111");


    /**********这里备注一下模板，方便对应
     *  String SMS_REGISTER_CONTENT = "感谢注册校招VIP，您的手机验证码是%s。Run for youth。";
     *
     */

    private final int id;
    private final String name;
    private final String templteId; //目前短信都调用第三方短信模板

    SmsTypeEnum(int id, String name, String templteId) {
        this.id = id;
        this.name = name;
        this.templteId = templteId;
    }

    public static SmsTypeEnum getById(int id) {
        for (SmsTypeEnum smsType : SmsTypeEnum.values()) {
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

    public String getTemplteId() {
        return templteId;
    }

}
