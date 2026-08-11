package vip.xiaozhao.intern.baseUtil.intf.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;

/**
 * Performs syntax-level validation only. The application never fetches the
 * supplied URL while handling the request, which avoids turning validation
 * into an SSRF primitive.
 */
public class ImageUrlsValidator implements ConstraintValidator<SafeImageUrls, String> {

    private int maxImages;

    @Override
    public void initialize(SafeImageUrls annotation) {
        this.maxImages = annotation.maxImages();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        String[] urls = value.split(",", -1);
        if (urls.length > maxImages) {
            return false;
        }

        for (String rawUrl : urls) {
            String url = rawUrl.trim();
            if (url.isEmpty() || url.length() > 512 || containsWhitespace(url)) {
                return false;
            }

            try {
                URI parsed = URI.create(url);
                String scheme = parsed.getScheme();
                if (scheme == null ||
                        !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ||
                        parsed.getHost() == null ||
                        parsed.getUserInfo() != null) {
                    return false;
                }
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return true;
    }

    private boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
