package vip.xiaozhao.intern.baseUtil.intf.utils.security;

import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.intf.utils.JjwtUtil;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base64CompatibilityTest {

    @Test
    void encodeAndDecode_ShouldUseStandardBase64() {
        byte[] input = {0, 1, 2, 3, -1, -128, 127};

        assertEquals("AAECA/+Afw==", Base64.encode(input));
        assertArrayEquals(input, Base64.decode("AAECA/+Afw=="));
    }

    @Test
    void decode_ShouldIgnoreLegacyMimeWhitespace() {
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                Base64.decode(" aGVs\r\nbG8=\t"));
    }

    @Test
    void nullAndInvalidInput_ShouldKeepLegacyReturnValues() {
        assertNull(Base64.encode(null));
        assertNull(Base64.decode(null));
        assertNull(Base64.decode("invalid"));
        assertNull(Base64.decode("%%%%"));
    }

    @Test
    void tripleDes_ShouldKeepExistingCiphertextCompatibility() {
        assertEquals("d1laAOGB9mA=", TripleDes.encryt("hello"));
        assertEquals("hello", TripleDes.decrypt("d1laAOGB9mA="));
        assertEquals("1084", TripleDes.decrypt("J6wEKQu8iMk="));
        assertEquals("round-trip", EncryptUtil.decrypt(EncryptUtil.encrypt("round-trip")));
    }

    @Test
    void rsaSignature_ShouldRoundTripGeneratedKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKey = Base64.encode(keyPair.getPrivate().getEncoded());
        String publicKey = Base64.encode(keyPair.getPublic().getEncoded());

        String signature = RSASignature.signature("payload", privateKey);

        assertTrue(RSASignature.doCheck("payload", signature, publicKey));
        assertFalse(RSASignature.doCheck("changed", signature, publicKey));
    }

    @Test
    void loginToken_ShouldRoundTripAfterCodecReplacement() throws Exception {
        String token = JjwtUtil.getLoginToken(8263);

        assertEquals(8263, JjwtUtil.verifyLoginToken(token));
        assertEquals(-1, JjwtUtil.verifyLoginToken("invalid"));
    }
}
