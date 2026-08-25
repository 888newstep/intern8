package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import java.nio.charset.StandardCharsets;

public class EncryptUtil {

    /**
     * 把对象先tripleDes加密，再base64编码
     *
     * @param content
     * @return
     */
    public static String encrypt(String content) {
        String encrypted = TripleDes.encryt(content);
        return encrypted == null ? null : Base64.encode(encrypted.getBytes(StandardCharsets.UTF_8));
    }

    public static String decrypt(String content) {
        byte[] decoded = Base64.decode(content);
        return decoded == null ? null : TripleDes.decrypt(new String(decoded, StandardCharsets.UTF_8));
    }

}
