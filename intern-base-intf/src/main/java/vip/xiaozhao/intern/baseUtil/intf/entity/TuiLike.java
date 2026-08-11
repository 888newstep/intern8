package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Data;
import java.util.Date;

@Data
public class TuiLike {
    private Long id;
    private Long userId;
    private Long targetId;
    private Integer targetType;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
