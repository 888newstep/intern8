package vip.xiaozhao.intern.baseUtil.intf.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a comma-separated image URL list without making outbound requests.
 */
@Documented
@Constraint(validatedBy = ImageUrlsValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeImageUrls {

    String message() default "图片URL格式不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int maxImages() default 9;
}
