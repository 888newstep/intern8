package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Data;

import java.util.Date;

@Data
public class MqMessageStatus {
    private Long id;
    private String messageId;
    private String eventType;
    private String businessKey;
    private String messageBody;
    private Integer status;
    private Integer retryCount;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
