package com.aigreentick.services.wabaaccounts.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for BSP (Business Solution Provider)
 * Used when not connecting directly to Meta
 */
@Configuration
@Getter
public class BspConfig {

    @Value("${bsp.enabled:true}")
    private boolean enabled;

    @Value("${bsp.provider:360dialog}")
    private String provider;

    @Value("${bsp.api-url:}")
    private String apiUrl;

    @Value("${bsp.api-key:}")
    private String apiKey;

    @Value("${bsp.partner-id:}")
    private String partnerId;

    public boolean isDirect() {
        return "direct".equalsIgnoreCase(provider);
    }
}