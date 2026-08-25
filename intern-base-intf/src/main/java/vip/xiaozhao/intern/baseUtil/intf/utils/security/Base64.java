package vip.xiaozhao.intern.baseUtil.intf.utils.security;

/**
 * Compatibility facade for legacy callers that expect null on invalid input.
 */
public final class Base64 {

    private Base64() {
    }

    public static String encode(byte[] binaryData) {
        return binaryData == null ? null : java.util.Base64.getEncoder().encodeToString(binaryData);
    }

    public static byte[] decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        String compact = encoded
                .replace(" ", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", "");
        if (compact.length() % 4 != 0) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
