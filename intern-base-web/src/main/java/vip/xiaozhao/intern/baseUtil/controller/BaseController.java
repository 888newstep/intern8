package vip.xiaozhao.intern.baseUtil.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;

public class BaseController {

    protected Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Number) {
            return ((Number) principal).longValue();
        }
        if (principal instanceof String) {
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    protected ResponseDO success(Object data) {
        return ResponseDO.success(data);
    }

    protected ResponseDO success(String message) {
        return new ResponseDO(true, message, null);
    }

    protected ResponseDO fail(String message) {
        return ResponseDO.fail(message);
    }

    protected ResponseDO fail(Integer errorCode, String message) {
        return ResponseDO.fail(errorCode, message);
    }

    protected ResponseDO unauthorized() {
        return fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
    }

    protected ResponseDO forbidden() {
        return fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage());
    }

}
