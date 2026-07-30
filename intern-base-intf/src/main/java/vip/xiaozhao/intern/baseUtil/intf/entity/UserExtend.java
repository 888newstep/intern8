package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

@Getter
@Setter
@ToString
public class UserExtend {
    private int id;
    private int userId;
    private int provinceId;
    private int cityId;
    private int collegeProvinceId;
    private int collegeId;
    private String degree;
    private String eduStartDate;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
