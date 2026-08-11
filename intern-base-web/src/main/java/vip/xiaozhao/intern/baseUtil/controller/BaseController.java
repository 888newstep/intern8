package vip.xiaozhao.intern.baseUtil.controller;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;

public class BaseController {

    protected static final String SUCCESS = "success";
    protected static final String FAIL = "fail";
    protected static final int SUCCESS_ID = 1;
    protected static final int FAIL_ID = 0;
    protected static final String NOT_LOGIN = "not_login";
    protected static final Gson gson = new Gson();

    @Value("${home.url}")
    protected String PreFix;

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
