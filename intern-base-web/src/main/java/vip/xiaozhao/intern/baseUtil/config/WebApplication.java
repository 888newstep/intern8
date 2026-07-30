package vip.xiaozhao.intern.baseUtil.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vip.xiaozhao.intern.baseUtil.controller.interceptor.PrehandleInterceptor;

@Configuration
public class WebApplication implements WebMvcConfigurer {
    private final PrehandleInterceptor prehandleInterceptor;

    public WebApplication(PrehandleInterceptor prehandleInterceptor) {
        this.prehandleInterceptor = prehandleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(prehandleInterceptor)
                .addPathPatterns("/**");
    }

    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}