package com.woobeee.mvc._common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        // apex 와 www 는 브라우저에게 서로 다른 오리진이다. www 가 빠져 있으면
                        // 브라우저 요청이 컨트롤러에 닿기도 전에 403 Invalid CORS request 로
                        // 잘리고, CORS 거부는 예외가 아니라서 로그에도 아무것도 남지 않는다.
                        "https://woobeee.com",
                        "https://www.woobeee.com"
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Location")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
