package com.wedding.upload.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRateLimiterTest {

    @Test
    void shouldAllowUpToLimitThenBlock() {
        SimpleRateLimiter limiter = new SimpleRateLimiter(3);
        assertTrue(limiter.tryConsume("ip-1"));
        assertTrue(limiter.tryConsume("ip-1"));
        assertTrue(limiter.tryConsume("ip-1"));
        assertFalse(limiter.tryConsume("ip-1"));
    }

    @Test
    void shouldKeepIndependentWindowsPerKey() {
        SimpleRateLimiter limiter = new SimpleRateLimiter(1);
        assertTrue(limiter.tryConsume("ip-1"));
        assertTrue(limiter.tryConsume("ip-2"));
        assertFalse(limiter.tryConsume("ip-1"));
        assertFalse(limiter.tryConsume("ip-2"));
    }
}