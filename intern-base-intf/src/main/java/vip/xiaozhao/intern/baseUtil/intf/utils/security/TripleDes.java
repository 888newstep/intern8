package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;

public class TripleDes {

    // 定义加密算法，有DES、DESede(即3DES)、Blowfish
    private static final String Algorithm = "DESede";
    private static final String PASSWORD_CRYPT_KEY = "wq$%^dfvdsc#$23fds32cde3";

    public static String encryt(String src) {
        try {
            return new String(Base64.encode(encryptMode(src.getBytes("utf-8"))));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decrypt(String src) {
        try {
            return new String(decryptMode(Base64.decode(src)));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 特殊使用，截取前8位
     *
     * @param src
     * @return
     */
    public static String get7BitEncryt(String src) {
        String encrypted = encryt(src);
        if (encrypted == null || encrypted.length() < 8) {
            return encrypted;
        }
        return encrypted.substring(0, 8);
    }

    /**
     * 加密方法
     *
     * @param src 源数据的字节数组
     * @return
     */
    public static byte[] encryptMode(byte[] src) {
        try {
            SecretKey deskey = new SecretKeySpec(
                    build3DesKey(PASSWORD_CRYPT_KEY), Algorithm); // 生成密钥
            Cipher c1 = Cipher.getInstance(Algorithm); // 实例化负责加密/解密的Cipher工具类
            c1.init(Cipher.ENCRYPT_MODE, deskey); // 初始化为加密模式
            return c1.doFinal(src);
        } catch (java.security.NoSuchAlgorithmException e1) {
            e1.printStackTrace();
        } catch (javax.crypto.NoSuchPaddingException e2) {
            e2.printStackTrace();
        } catch (Exception e3) {
            e3.printStackTrace();
        }
        return null;
    }

    /**
     * 解密函数
     *
     * @param src 密文的字节数组
     * @return
     */
    public static byte[] decryptMode(byte[] src) {
        try {
            SecretKey deskey = new SecretKeySpec(
                    build3DesKey(PASSWORD_CRYPT_KEY), Algorithm);
            Cipher c1 = Cipher.getInstance(Algorithm);
            c1.init(Cipher.DECRYPT_MODE, deskey); // 初始化为解密模式
            return c1.doFinal(src);
        } catch (java.security.NoSuchAlgorithmException e1) {
            e1.printStackTrace();
        } catch (javax.crypto.NoSuchPaddingException e2) {
            e2.printStackTrace();
        } catch (Exception e3) {
            e3.printStackTrace();
        }
        return null;
    }

    /*
     * 根据字符串生成密钥字节数组
     *
     * @param keyStr 密钥字符串
     *
     * @return
     *
     * @throws UnsupportedEncodingException
     */
    public static byte[] build3DesKey(String keyStr)
            throws UnsupportedEncodingException {
        byte[] key = new byte[24]; // 声明一个24位的字节数组，默认里面都是0
        byte[] temp = keyStr.getBytes("UTF-8"); // 将字符串转成字节数组

        /*
         * 执行数组拷贝 System.arraycopy(源数组，从源数组哪里开始拷贝，目标数组，拷贝多少位)
         */
        if (key.length > temp.length) {
            // 如果temp不够24位，则拷贝temp数组整个长度的内容到key数组中
            System.arraycopy(temp, 0, key, 0, temp.length);
        } else {
            // 如果temp大于24位，则拷贝temp数组24个长度的内容到key数组中
            System.arraycopy(temp, 0, key, 0, key.length);
        }
        return key;
    }


    public static void main(String[] args) {
        String aa = "23456789oiujyhgtfrdew324sdfawe";
        String enString = encryt(aa);
        String deString = decrypt("J6wEKQu8iMk=");
        System.out.println(enString);
        System.out.println(deString);
    }
}
