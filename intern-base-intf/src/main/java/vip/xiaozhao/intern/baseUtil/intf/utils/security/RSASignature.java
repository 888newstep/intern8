package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public final class RSASignature {

    public static final String SIGN_ALGORITHMS = "SHA1WithRSA";

    private RSASignature() {
    }

    public static String signature(String content, String privateKey) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privateKey)));
        java.security.Signature signature = java.security.Signature.getInstance(SIGN_ALGORITHMS);
        signature.initSign(key);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.encode(signature.sign());
    }

    public static boolean doCheck(String content, String sign, String publicKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(Base64.decode(publicKey)));
            java.security.Signature signature = java.security.Signature.getInstance(SIGN_ALGORITHMS);
            signature.initVerify(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.decode(sign));
        } catch (Exception exception) {
            return false;
        }
    }
}
