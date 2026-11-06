package com.woobeee.mvc.ai.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 워커 시크릿 필터를 {@code /internal/ai/*} 에만, 접근 로그 필터 바로 안쪽 순서로 건다. */
@Configuration
public class AiFilterConfig {

    @Bean
    public FilterRegistrationBean<AiWorkerSecretFilter> aiWorkerSecretFilter(AiProperties properties) {
        FilterRegistrationBean<AiWorkerSecretFilter> registration =
                new FilterRegistrationBean<>(new AiWorkerSecretFilter(properties));
        registration.addUrlPatterns("/internal/ai/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
