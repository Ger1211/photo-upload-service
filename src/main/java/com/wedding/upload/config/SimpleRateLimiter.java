package com.wedding.upload.config;

import java.time.Instant;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleRateLimiter {

    private final int maxRequests;
    private final long windowSeconds;
    private final Map<String, LinkedList<Instant>> timestamps = new ConcurrentHashMap<>();

    public SimpleRateLimiter(int maxRequestsPerMinute) {
        this.maxRequests = maxRequestsPerMinute;
        this.windowSeconds = 60;
    }

    public synchronized boolean tryConsume(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(windowSeconds);

        LinkedList<Instant> list = timestamps.computeIfAbsent(key, k -> new LinkedList<>());

        list.removeIf(t -> t.isBefore(cutoff));

        if (list.size() < maxRequests) {
            list.add(now);
            return true;
        }

        return false;
    }
}
