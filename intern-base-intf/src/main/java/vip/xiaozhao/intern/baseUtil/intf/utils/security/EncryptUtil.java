package vip.xiaozhao.intern.baseUtil.intf.utils.security;

public class EncryptUtil {

    /**
     * 把对象先tripleDes加密，再base64编码
     *
     * @param content
     * @return
     */
    public static String encrypt(String content) {
        return Base64.encode(TripleDes.encryt(content).getBytes());
    }

    public static String decrypt(String content) {
        return TripleDes.decrypt(new String(Base64.decode(content)));
    }

}
