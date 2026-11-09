package com.example.schedulingsoftware.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class LiveWebConfig implements WebMvcConfigurer {

    private final LiveChangeInterceptor liveChangeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(liveChangeInterceptor).addPathPatterns("/api/**");
    }
}
