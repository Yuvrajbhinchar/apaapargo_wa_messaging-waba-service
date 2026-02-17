package com.aigreentick.services.wabaaccounts.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Meta Graph API
 */
@Configuration
@Getter
public class MetaApiConfig {

    @Value("${meta.app-id}")
    private String appId;

    @Value("${meta.app-secret}")
    private String appSecret;

    @Value("${meta.api-version}")
    private String apiVersion;

    @Value("${meta.graph-api-base-url}")
    private String graphApiBaseUrl;

    @Value("${meta.webhook-verify-token}")
    private String webhookVerifyToken;

    /**
     * Build full Graph API URL
     */
    public String buildUrl(String path) {
        return graphApiBaseUrl + "/" + apiVersion + "/" + path;
    }
}