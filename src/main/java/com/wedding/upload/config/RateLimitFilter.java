package com.wedding.upload.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final SimpleRateLimiter rateLimiter;

    public RateLimitFilter(SimpleRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        if (path.equals("/api/upload") && "POST".equalsIgnoreCase(httpRequest.getMethod())) {
            String clientIp = httpRequest.getRemoteAddr();

            if (rateLimiter.tryConsume(clientIp)) {
                chain.doFilter(request, response);
            } else {
                log.warn("Rate limit exceeded from IP: {}", clientIp);
                httpResponse.setStatus(429);
                httpResponse.setContentType("application/json");
                httpResponse.setCharacterEncoding("UTF-8");
                httpResponse.getWriter().write(
                        "{\"success\":false,\"uploaded\":0,\"failed\":0," +
                                "\"message\":\"Demasiadas solicitudes. Intente nuevamente en un minuto.\"}"
                );
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
