package vip.xiaozhao.intern.baseUtil.controller;

import com.google.gson.Gson;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Value;
import vip.xiaozhao.intern.baseUtil.intf.constant.CommonConstant;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;

import jakarta.servlet.http.HttpServletRequest;

public class BaseController {

    protected static final String SUCCESS = "success";
    protected static final String FAIL = "fail";
    protected static final int SUCCESS_ID = 1;
    protected static final int FAIL_ID = 0;
    protected static final String NOT_LOGIN = "not_login";
    protected static final Gson gson = new Gson();

    @Value("${home.url}")
    protected String PreFix;

    protected int getCurrentUserId(HttpServletRequest request){
        Object uId = request.getAttribute(CommonConstant.LOGIN_USERID_KEY);
        if (uId == null){
            return -1;
        }else {
            return NumberUtils.toInt(uId.toString());
        }
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


}
