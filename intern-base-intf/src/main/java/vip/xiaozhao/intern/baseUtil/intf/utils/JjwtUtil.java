package vip.xiaozhao.intern.baseUtil.intf.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import vip.xiaozhao.intern.baseUtil.intf.constant.SignKeyConstant;
import vip.xiaozhao.intern.baseUtil.intf.utils.security.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HexFormat;

/**
 * 兼容旧登录令牌格式的签发与校验工具。
 *
 * <p>令牌仍使用 {@code userId^timestamp^signature} 格式，但签名已从可碰撞的 MD5
 * 升级为 HMAC-SHA256。升级部署后，历史 MD5 令牌会失效，用户需要重新登录。</p>
 */
@Slf4j
public final class JjwtUtil {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final long TOKEN_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_TOKEN_LENGTH = 512;

    private JjwtUtil() {
    }

    public static int verifyLoginToken(String token) {
        if (StringUtils.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
            return -1;
        }
        try {
            byte[] decoded = Base64.decode(token);
            if (decoded == null) {
                return -1;
            }
            String decodedToken = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = decodedToken.split("\\^", -1);
            if (parts.length != 3) {
                log.warn("User cookie value length is not 3");
                return -1;
            }

            String expectedSignature = sign(parts[1] + parts[0]);
            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.US_ASCII),
                    parts[2].getBytes(StandardCharsets.US_ASCII))) {
                log.warn("User cookie HMAC verification failed");
                return -1;
            }

            Date issuedAt = new SimpleDateFormat("yyyyMMddHHmmss").parse(parts[1]);
            long ageMs = System.currentTimeMillis() - issuedAt.getTime();
            if (ageMs < 0 || ageMs > TOKEN_VALIDITY_MS) {
                return 0;
            }
            int userId = Integer.parseInt(parts[0]);
            return userId > 0 ? userId : -1;
        } catch (Exception exception) {
            log.warn("User cookie verification failed: {}", exception.getClass().getSimpleName());
            return -1;
        }
    }

    public static String getLoginToken(int userId) throws Exception {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id must be positive");
        }
        String date = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String token = userId + "^" + date + "^" + sign(date + userId);
        return Base64.encode(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String content) throws Exception {
        String key = SignKeyConstant.LOGIN_TIME_KEY;
        if (StringUtils.isBlank(key) || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("login.time.key must contain at least 32 bytes");
        }
        Mac mac = Mac.getInstance(HMAC_SHA_256);
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
