package vip.xiaozhao.intern.baseUtil.intf.utils;

import lombok.extern.slf4j.Slf4j;
import vip.xiaozhao.intern.baseUtil.intf.constant.SignKeyConstant;
import vip.xiaozhao.intern.baseUtil.intf.utils.security.Base64;
import vip.xiaozhao.intern.baseUtil.intf.utils.security.MD5Signature;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

@Slf4j
public class JjwtUtil {


    public static int verifyLoginToken(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        try {
            byte[] decoded = Base64.decode(token);
            if (decoded == null) {
                return -1;
            }
            String decodeToken = new String(decoded, StandardCharsets.UTF_8);
            String[] timeSplit = decodeToken.split("\\^");
            if (timeSplit.length != 3) {
                log.warn("User cookie value length is not 3");
                return -1;
            }
            if (!MD5Signature.verify(timeSplit[1] + timeSplit[0],
                    timeSplit[2], SignKeyConstant.LOGIN_TIME_KEY)) {
                log.warn("User cookie MD5 verification failed");
                return -1;
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
            Date date = format.parse(timeSplit[1]);
            //30天      60*60 *24 * 30 * 1000
            if (date == null || ((new Date()).getTime() - date.getTime()) > 2592000000L) {
                return 0;
            }
            return Integer.parseInt(timeSplit[0]);
        } catch (Exception e) {
            log.error("User cookie verification failed");
            return -1;
        }
    }

    public static String getLoginToken(int userId) throws Exception {
        String date = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String token = userId + "^" + date + "^" + MD5Signature.sign(date + userId, SignKeyConstant.LOGIN_TIME_KEY);
        return Base64.encode(token.getBytes(StandardCharsets.UTF_8));
    }

}
