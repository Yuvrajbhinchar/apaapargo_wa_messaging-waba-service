package com.aigreentick.services.wabaaccounts.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Meta (Facebook) Cloud API
 *
 * Direct integration — AiGreenTick is a Meta Tech Partner.
 * No BSP (Business Solution Provider) middleware required.
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
     * Builds a full Graph API URL for a given path
     * Example: buildUrl("123456/phone_numbers") →
     *          https://graph.facebook.com/v21.0/123456/phone_numbers
     */
    public String buildUrl(String path) {
        return graphApiBaseUrl + "/" + apiVersion + "/" + path;
    }
}