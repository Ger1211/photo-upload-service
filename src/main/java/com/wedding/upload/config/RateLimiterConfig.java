package com.wedding.upload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimiterConfig {

    private final RateLimitProperties rateLimitProperties;

    public RateLimiterConfig(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @Bean
    public SimpleRateLimiter simpleRateLimiter() {
        return new SimpleRateLimiter(rateLimitProperties.getUploadsPerMinute());
    }
}
