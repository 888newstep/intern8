package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
@ToString
public class User implements Serializable {
    private int id;
    private String userName;
    private String password;
    private String brief;
    private int gender;
    private String birthday;
    private int rank;
    private String mobile;
    private int mobileStatus;
    private String email;
    private int emailStatus;
    private int status;
    private String photoAddress;
    private String salt;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date addTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastLoginTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
    private String userIp;

}
