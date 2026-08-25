package vip.xiaozhao.intern.baseUtil.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaozhao.intern.baseUtil.config.security.JwtTokenProvider;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地演示认证入口。仅在 dev Profile 加载，不提供生产用户认证能力。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/user")
public class DevAuthController extends BaseController {

    private final JwtTokenProvider jwtTokenProvider;
    private final String demoPassword;
    private final long expirationMs;

    public DevAuthController(JwtTokenProvider jwtTokenProvider,
                             @Value("${demo.auth.password}") String demoPassword,
                             @Value("${jwt.expiration:86400000}") long expirationMs) {
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalArgumentException("demo.auth.password must not be blank in dev profile");
        }
        this.jwtTokenProvider = jwtTokenProvider;
        this.demoPassword = demoPassword;
        this.expirationMs = expirationMs;
    }

    @PostMapping("/login")
    public ResponseDO login(@Valid @RequestBody DevLoginRequest request) {
        // 演示密码只用于本地环境，不查询用户表，也不写入日志。
        if (!demoPassword.equals(request.getPassword())) {
            return fail(401, "Invalid demo credentials");
        }

        String token = jwtTokenProvider.generateToken(request.getUserId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("tokenType", "Bearer");
        result.put("expiresInMs", expirationMs);
        result.put("userId", request.getUserId());
        return success(result);
    }

    public static class DevLoginRequest {
        @NotNull
        @Positive
        private Long userId;

        @NotBlank
        private String password;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
