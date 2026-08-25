package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class TripleDes {

    private static final String ALGORITHM = "DESede";
    private static final String PASSWORD_CRYPT_KEY = "wq$%^dfvdsc#$23fds32cde3";

    private TripleDes() {
    }

    public static String encryt(String source) {
        return source == null ? null : Base64.encode(encryptMode(source.getBytes(StandardCharsets.UTF_8)));
    }

    public static String decrypt(String source) {
        byte[] decrypted = source == null ? null : decryptMode(Base64.decode(source));
        return decrypted == null ? null : new String(decrypted, StandardCharsets.UTF_8);
    }

    public static String get7BitEncryt(String source) {
        String encrypted = encryt(source);
        return encrypted == null || encrypted.length() < 8 ? encrypted : encrypted.substring(0, 8);
    }

    public static byte[] encryptMode(byte[] source) {
        return crypt(source, Cipher.ENCRYPT_MODE);
    }

    public static byte[] decryptMode(byte[] source) {
        return crypt(source, Cipher.DECRYPT_MODE);
    }

    private static byte[] crypt(byte[] source, int mode) {
        if (source == null) {
            return null;
        }
        try {
            SecretKey key = new SecretKeySpec(build3DesKey(PASSWORD_CRYPT_KEY), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, key);
            return cipher.doFinal(source);
        } catch (GeneralSecurityException | UnsupportedEncodingException exception) {
            return null;
        }
    }

    public static byte[] build3DesKey(String key) throws UnsupportedEncodingException {
        return Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 24);
    }
}
