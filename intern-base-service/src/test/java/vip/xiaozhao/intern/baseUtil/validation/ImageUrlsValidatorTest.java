package vip.xiaozhao.intern.baseUtil.validation;

import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.intf.validation.ImageUrlsValidator;
import vip.xiaozhao.intern.baseUtil.intf.validation.SafeImageUrls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUrlsValidatorTest {

    private final ImageUrlsValidator validator = new ImageUrlsValidator();

    ImageUrlsValidatorTest() throws NoSuchFieldException {
        SafeImageUrls annotation = TestRequest.class.getDeclaredField("images")
                .getAnnotation(SafeImageUrls.class);
        validator.initialize(annotation);
    }

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertTrue(validator.isValid(
                "https://cdn.example.com/a.png,http://localhost:8080/b.jpg", null));
    }

    @Test
    void rejectsNonHttpSchemesAndCredentials() {
        assertFalse(validator.isValid("javascript:alert(1)", null));
        assertFalse(validator.isValid("https://user:pass@example.com/a.png", null));
    }

    @Test
    void rejectsBlankItemsAndTooManyImages() {
        assertFalse(validator.isValid("https://example.com/a.png,", null));
        assertFalse(validator.isValid(
                "https://example.com/1.png,https://example.com/2.png,https://example.com/3.png," +
                        "https://example.com/4.png,https://example.com/5.png,https://example.com/6.png," +
                        "https://example.com/7.png,https://example.com/8.png,https://example.com/9.png," +
                        "https://example.com/10.png", null));
    }

    private static class TestRequest {
        @SafeImageUrls
        private String images;
    }
}
