package com.aigreentick.services.wabaaccounts.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Meta (Facebook) Cloud API.
 *
 * AiGreenTick is a Meta Tech Partner — direct Cloud API integration, no BSP.
 *
 * All sensitive values MUST come from environment variables in production:
 *   META_APP_ID, META_APP_SECRET, WEBHOOK_VERIFY_TOKEN
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
     * Optional: Embedded Signup Configuration ID from Meta App Dashboard.
     * Set via META_EMBEDDED_SIGNUP_CONFIG_ID env var.
     * Frontend uses this as the config_id in FB.login() call.
     */
    @Value("${meta.embedded-signup-config-id:}")
    private String embeddedSignupConfigId;
}