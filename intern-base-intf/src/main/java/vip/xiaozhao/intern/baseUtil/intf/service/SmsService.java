package vip.xiaozhao.intern.baseUtil.intf.service;

import vip.xiaozhao.intern.baseUtil.intf.entity.Sms;
import vip.xiaozhao.intern.baseUtil.intf.enums.SmsTypeEnum;

public interface SmsService {

    public int insert(Sms sms);

    /**
     * 注册绑定手机
     *
     * @param mobile
     * @param veriCode
     */
    public int sendBindSms(String mobile, String veriCode, SmsTypeEnum smsType, int userId, String userIp);

    /**
     * 暂时不考虑校验失败的情况（2.0接口增加验证流水）
     *
     * @param mobile
     * @param smsType
     * @param userId
     * @param userIp
     * @return
     */

    public int sendSms(String mobile, SmsTypeEnum smsType, int userId, String userIp);


    public Sms loadById(int id);

    public Sms loadLatestSmsByMobile(String mobile);

}
