package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class RSASignature {


    // 商户（RSA）私钥

    public static final String SIGN_ALGORITHMS = "SHA1WithRSA";

    /**
     * RSA加密
     *
     * @param content    待加密数据
     * @param privateKey 商户私钥
     * @return 密文
     * @throws NoSuchAlgorithmException
     */
    public static String signature(String content, String privateKey) throws Exception {
        String charset = "utf-8";
        PKCS8EncodedKeySpec priPkCS8 = new PKCS8EncodedKeySpec(
                Base64.decode(privateKey));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey priKey = keyFactory.generatePrivate(priPkCS8);

        java.security.Signature signature = java.security.Signature
                .getInstance(SIGN_ALGORITHMS);
        signature.initSign(priKey);
        signature.update(content.getBytes(charset));

        byte[] signed = signature.sign();

        return Base64.encode(signed);
    }


    /**
     * RSA验签名检查
     *
     * @param content   待签名数据
     * @param sign      签名值
     * @param publicKey 支付宝公钥
     * @return 布尔值
     */
    public static boolean doCheck(String content, String sign, String publicKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            byte[] encodedKey = Base64.decode(publicKey);
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey));


            java.security.Signature signature = java.security.Signature
                    .getInstance(SIGN_ALGORITHMS);

            signature.initVerify(pubKey);
            signature.update(content.getBytes("utf-8"));

            boolean bverify = signature.verify(Base64.decode(sign));
            return bverify;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public static void main(String[] args) throws Exception {
        final String RSA_PUBLIC = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCvo6BCaH0wQxGwQ2mh/V8f/mLNx2ZiY7F3jstXougowwOz3FxnvWSpgax/5TnsR40sTuCsZjAsX39brxAU+ZSP3XkdZPh8ROl/EomQZ0ZZKfYlPSBLlWkB/teNLjqNfRV54LmivnTd8rz4XHHOD1A8UTtvJ+CyTesvbKbmqlfQNwIDAQAB";
        String RSA_PRIVATE = "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAK+joEJofTBDEbBDaaH9Xx/+Ys3HZmJjsXeOy1ei6CjDA7PcXGe9ZKmBrH/lOexHjSxO4KxmMCxff1uvEBT5lI/deR1k+HxE6X8SiZBnRlkp9iU9IEuVaQH+140uOo19FXnguaK+dN3yvPhccc4PUDxRO28n4LJN6y9spuaqV9A3AgMBAAECgYEAmDEcqO6Jq8wyA54mZit6xepUCyOBYDgqiwK8yE9tBAUXrD6CA5JlK0wmARzLuCW3+nDO1hQmIg0N+pATS1mIlEELgp830MINzhwDzm9xn2xbPoAVda7emc7YWjB70KEAMxDCX92vvTNZBakguiltxzt01hQ1dsxYHf3JgXQBrqECQQDbl/HOJocERvC6NvQDEX3QqyDjf8uN7WpDYYb+ATy/dzrr6uuZpaePtA/wUyfCf+LcsRH7jWVocPxwyyPVnQMbAkEAzMIoIfrfaEaXmvE1ilgGOEzu/DizOHTT4WP8zteYV42smrsztVsE9x8VKwN16RjoOhSbtA3dG6Z5pH7q9p2dFQJAQK544ygmDPR4Y+Fb7qtwS86cWWaDJsMP0dkgUiE7K4qLKIKB8zgAKyv2petYgsn4oNjnWxlDDYh6Ux8C5yDgkQJBAMg1nGpFVEcwpzKgYfqowUQxrYg/pLYyPa7oRpy1YEdTxw7wtsnAeuVrphYN5zAJ46BO4EQ5Pha4O77lPrfvO1UCQGc1Ww18Ja4KReB9843BDjk6kEp7iQFyvnkgVOpXfENatocWJ1hcLjdO0lxSD/wGhED41mMZvBSbE11GqaZABS0=";
        final String testInfo = "partner=\"1111\"&seller=\"22222\"&ok=\"3333\"";
        String encryptString = signature(testInfo, RSA_PRIVATE);
        System.out.println(encryptString);
        System.out.println(doCheck(testInfo, encryptString, RSA_PUBLIC));

    }

}
