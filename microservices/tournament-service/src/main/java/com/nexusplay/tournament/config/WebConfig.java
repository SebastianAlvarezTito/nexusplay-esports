package com.nexusplay.tournament.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DelaySimulator delaySimulator;

    public WebConfig(DelaySimulator delaySimulator) {
        this.delaySimulator = delaySimulator;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Solo rutas de negocio. /health y /metrics quedan fuera a propósito.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                delaySimulator.applyDelay();
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
