package vip.xiaozhao.intern.baseUtil.controller;

import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.config.security.JwtTokenProvider;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevAuthControllerTest {

    private static final String JWT_SECRET = "local-test-secret-with-at-least-32-characters";
    private static final String DEMO_PASSWORD = "demo-password";

    @Test
    void loginReturnsUsableJwtForValidDemoCredentials() {
        JwtTokenProvider tokenProvider = new JwtTokenProvider(JWT_SECRET, "intern-base", 60_000L);
        DevAuthController controller = new DevAuthController(tokenProvider, DEMO_PASSWORD, 60_000L);
        DevAuthController.DevLoginRequest request = request(7L, DEMO_PASSWORD);

        ResponseDO response = controller.login(request);

        assertTrue(response.isSuccess());
        Map<?, ?> data = (Map<?, ?>) response.getData();
        String token = (String) data.get("token");
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(7L, tokenProvider.getUserIdFromToken(token));
        assertEquals("Bearer", data.get("tokenType"));
    }

    @Test
    void loginRejectsInvalidPasswordWithoutIssuingToken() {
        JwtTokenProvider tokenProvider = new JwtTokenProvider(JWT_SECRET, "intern-base", 60_000L);
        DevAuthController controller = new DevAuthController(tokenProvider, DEMO_PASSWORD, 60_000L);

        ResponseDO response = controller.login(request(7L, "wrong-password"));

        assertFalse(response.isSuccess());
        assertEquals(401, response.getErrorCode());
        assertEquals(null, response.getData());
    }

    private DevAuthController.DevLoginRequest request(Long userId, String password) {
        DevAuthController.DevLoginRequest request = new DevAuthController.DevLoginRequest();
        request.setUserId(userId);
        request.setPassword(password);
        return request;
    }
}
