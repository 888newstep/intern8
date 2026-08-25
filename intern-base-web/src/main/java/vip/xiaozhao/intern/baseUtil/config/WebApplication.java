package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class WebApplication {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
