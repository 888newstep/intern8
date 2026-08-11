package vip.xiaozhao.intern.baseUtil.intf.entity;

import lombok.Data;

import java.util.Date;

@Data
public class MqOutbox {
    private Long id;
    private String eventId;
    private String eventType;
    private String exchangeName;
    private String routingKey;
    private String messageBody;
    private Integer status;
    private Integer retryCount;
    private Date nextAttemptTime;
    private Date leaseUntil;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
