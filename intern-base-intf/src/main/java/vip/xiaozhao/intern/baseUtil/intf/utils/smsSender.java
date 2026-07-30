package vip.xiaozhao.intern.baseUtil.intf.utils;

import lombok.extern.slf4j.Slf4j;
import vip.xiaozhao.intern.baseUtil.intf.enums.SmsTypeEnum;

@Slf4j
public class smsSender {

    /***
     *
     * @param mobileNo
     * @param typeEnum
     * @param veriCode, 非验证码使用时可空
     * @return
     */
    public boolean sendSms(String mobileNo, SmsTypeEnum typeEnum, String veriCode) {
        log.info("sms info=:" + "mobileNo="+ mobileNo + " , SmsTypeEnum=:" +typeEnum.getName() +" ,veriCode=:" +veriCode);
        return true;
    }


}
