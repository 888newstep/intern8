package vip.xiaozhao.intern.baseUtil.intf.constant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SignKeyConstant {

    public static String LOGIN_TIME_KEY;

    @Value("${login.time.key}")
    public void setLoginTimeKey(String loginTimeKey) {
        LOGIN_TIME_KEY = loginTimeKey;
    }
}
