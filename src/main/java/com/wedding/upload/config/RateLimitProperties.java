package com.wedding.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private int uploadsPerMinute = 10;

    public int getUploadsPerMinute() {
        return uploadsPerMinute;
    }

    public void setUploadsPerMinute(int uploadsPerMinute) {
        this.uploadsPerMinute = uploadsPerMinute;
    }
}
