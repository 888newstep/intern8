package vip.xiaozhao.intern.baseUtil.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(SecurityConfigTest.TestConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "demo.auth.enabled=true",
        "security.cors.allowed-origins=http://localhost:3000"
})
class SecurityConfigTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(applicationContext).apply(springSecurity()).build();
        when(jwtTokenProvider.getUserIdIfValid(anyString())).thenReturn(null);
        when(jwtTokenProvider.getUserIdIfValid("valid-token")).thenReturn(42L);
    }

    @Test
    void publicLoginPostDoesNotRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedCookieStylePostWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/protected"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedBearerPostDoesNotRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/protected")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerDocsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/protected")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    void corsRejectsUntrustedOrigin() throws Exception {
        mockMvc.perform(options("/protected")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
            return new JwtAuthenticationFilter(tokenProvider);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @PostMapping("/api/user/login")
        String login() {
            return "ok";
        }

        @PostMapping("/protected")
        String protectedPost() {
            return "ok";
        }

        @org.springframework.web.bind.annotation.GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @org.springframework.web.bind.annotation.GetMapping("/actuator/metrics")
        String metrics() {
            return "metrics";
        }

        @org.springframework.web.bind.annotation.GetMapping("/v3/api-docs")
        String apiDocs() {
            return "{}";
        }
    }
}
