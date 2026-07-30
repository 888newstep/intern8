package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "OSS管理")
@RestController
@RequestMapping("/api/oss")
public class OSSController extends BaseController {

    @Operation(summary = "生成上传凭证", description = "生成OSS直传的临时凭证")
    @PostMapping("/getUploadToken")
    public ResponseDO getUploadToken(@Parameter(description = "请求参数", required = true) @RequestBody UploadTokenRequest request) {
        if (request.getUserId() == null) {
            return fail("参数错误");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tmpSecretId", "mock_secret_id_" + request.getUserId());
        result.put("tmpSecretKey", "mock_secret_key_" + request.getUserId());
        result.put("sessionToken", "mock_session_token");
        result.put("expiredTime", System.currentTimeMillis() / 1000 + 1800);

        return success(result);
    }

    public static class UploadTokenRequest {
        private Long userId;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }
    }
}