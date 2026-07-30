
package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UnsupportedEncodingException;
import java.security.SignatureException;

public class MD5Signature {
    public MD5Signature() {
    }

    public static String sign(String content, String key) throws Exception {
        return sign(content, key, "utf-8");
    }

    public static String sign(String content, String key, String charset) throws Exception {
        String tosign = (content == null ? "" : content) + key;

        try {
            return DigestUtils.md5Hex(getContentBytes(tosign, charset));
        } catch (UnsupportedEncodingException var5) {
            throw new SignatureException(" MD5 Exception [content = " + content + "; charset = utf-8]Exception!", var5);
        }
    }

    public static String signForWeixin(String content, String key, String charset) throws Exception {
        String tosign = (content == null ? "" : content) + "&key=" + key;

        try {
            return DigestUtils.md5Hex(getContentBytes(tosign, charset));
        } catch (UnsupportedEncodingException var5) {
            throw new SignatureException(" MD5 Exception [content = " + content + "; charset = utf-8]Exception!", var5);
        }
    }

    public static String signFor19bit(String content, String key) throws Exception {
        String signString = sign(content, key);
        signString = signString.substring(0, 19);
        return signString;
    }

    public static String md5Direct(String content) throws Exception {
        try {
            return DigestUtils.md5Hex(getContentBytes(content, "utf-8"));
        } catch (UnsupportedEncodingException var2) {
            throw new SignatureException(" MD5 Exception [content = " + content + "; charset = utf-8]Exception!", var2);
        }
    }

    public static boolean verify(String content, String sign, String key) throws Exception {
        return verify(content, sign, key, "utf-8");
    }

    public static boolean verifyFor19Bit(String content, String sign, String key) throws Exception {
        String tosign = (content == null ? "" : content) + key;

        try {
            String mySign = DigestUtils.md5Hex(getContentBytes(tosign, "utf-8"));
            mySign = mySign.substring(0, 19);
            return StringUtils.equals(mySign, sign);
        } catch (UnsupportedEncodingException var5) {
            throw new SignatureException("MD5Exception[content = " + content + "; charset =utf-8; signature = " + sign + "]Exception!", var5);
        }
    }

    public static boolean verify(String content, String sign, String key, String charset) throws Exception {
        String tosign = (content == null ? "" : content) + key;

        try {
            String mySign = DigestUtils.md5Hex(getContentBytes(tosign, charset));
            return StringUtils.equals(mySign, sign);
        } catch (UnsupportedEncodingException var6) {
            throw new SignatureException("MD5Exception[content = " + content + "; charset =" + charset + "; signature = " + sign + "]Exception!", var6);
        }
    }

    protected static byte[] getContentBytes(String content, String charset) throws UnsupportedEncodingException {
        return StringUtils.isEmpty(charset) ? content.getBytes() : content.getBytes(charset);
    }

    public static int avg(int d, long m) {
        return d / (int) m;
    }
}
