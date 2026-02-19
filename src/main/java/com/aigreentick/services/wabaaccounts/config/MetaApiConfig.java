package com.aigreentick.services.wabaaccounts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Typed config properties for the Meta (Facebook) Graph API.
 *
 * Bound from application.yaml under prefix "meta":
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  meta:                                                          │
 * │    app-id:              ${META_APP_ID}                         │
 * │    app-secret:          ${META_APP_SECRET}                     │
 * │    graph-api-version:   v18.0                                  │
 * │    graph-api-base-url:  https://graph.facebook.com             │
 * │    webhook-verify-token: ${WEBHOOK_VERIFY_TOKEN}               │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * All sensitive values come from environment variables — never hardcoded.
 * See MetaConfigValidator for fail-fast startup validation.
 */
@Configuration
@ConfigurationProperties(prefix = "meta")
@Data
public class MetaApiConfig {

    /** Meta App ID — found in Meta for Developers dashboard */
    private String appId;

    /** Meta App Secret — treat as a password, never log or expose */
    private String appSecret;

    /** Graph API version to use for all calls (e.g. "v18.0") */
    private String graphApiVersion = "v18.0";

    /** Base URL for Graph API (override in tests with WireMock) */
    private String graphApiBaseUrl = "https://graph.facebook.com";

    /** Token for webhook payload verification */
    private String webhookVerifyToken;

    /**
     * The Embedded Signup Configuration ID from Meta Business Suite.
     * Found at: Meta Business Suite → WhatsApp → Embedded Signup → Configuration ID
     * Passed to the frontend SDK to render the correct signup flow.
     * Env var: META_EMBEDDED_SIGNUP_CONFIG_ID
     */
    private String embeddedSignupConfigId;

    /**
     * Build the full versioned Graph API base URL.
     * Example: https://graph.facebook.com/v18.0
     */
    public String getVersionedBaseUrl() {
        return graphApiBaseUrl + "/" + graphApiVersion;
    }

    /**
     * Alias for graphApiVersion — used by EmbeddedSignupService.getSignupConfig()
     * to return the version to the frontend SDK.
     */
    public String getApiVersion() {
        return graphApiVersion;
    }
}