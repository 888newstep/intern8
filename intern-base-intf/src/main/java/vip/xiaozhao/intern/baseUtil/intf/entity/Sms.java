package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

@Setter
@ToString
@Getter
public class Sms {

    private int id;
    private int userId;
    private String mobile;
    //放第三方模板值
    private String content;
    private String veriCode;
    private int smsType;
    private int status;//见枚举 0 1 2
    private int code;//操作代码
    private String message;//操作描述
    private Date createTime;
    private String userIp;
}
