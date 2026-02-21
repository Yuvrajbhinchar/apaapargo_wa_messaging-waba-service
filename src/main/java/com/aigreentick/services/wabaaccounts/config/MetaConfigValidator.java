package com.aigreentick.services.wabaaccounts.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast validation for required Meta API configuration.
 * Solution: Validate at startup. If any required config is missing or is still
 * the placeholder value, the application context will fail to load with a
 * clear, actionable error message.
 *
 * Required env vars:
 *   - META_APP_ID          → meta.app-id
 *   - META_APP_SECRET      → meta.app-secret
 *   - WEBHOOK_VERIFY_TOKEN → meta.webhook-verify-token
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class MetaConfigValidator {

    private final MetaApiConfig metaApiConfig;

    @org.springframework.beans.factory.annotation.Value("${token.encryption.key:}")
    private String tokenEncryptionKey;

    // Placeholder values that indicate config was not properly set
    private static final java.util.Set<String> PLACEHOLDER_VALUES = java.util.Set.of(
            "",
            "your-meta-app-id",
            "your-meta-app-secret",
            "aigreentick-verify-token",
            "null",
            "undefined",
            "${META_APP_ID}",
            "${META_APP_SECRET}",
            "${WEBHOOK_VERIFY_TOKEN}"
    );

    @PostConstruct
    public void validateMetaConfig() {
        log.info("Validating Meta API configuration...");

        validateRequired("META_APP_ID", "meta.app-id", metaApiConfig.getAppId());
        validateRequired("META_APP_SECRET", "meta.app-secret", metaApiConfig.getAppSecret());
        validateRequired("WEBHOOK_VERIFY_TOKEN", "meta.webhook-verify-token", metaApiConfig.getWebhookVerifyToken());
        validateRequired("TOKEN_ENCRYPTION_KEY", "token.encryption.key", tokenEncryptionKey);

        // Warn about optional but recommended config
        if (isNullOrPlaceholder(metaApiConfig.getEmbeddedSignupConfigId())) {
            log.warn("META_EMBEDDED_SIGNUP_CONFIG_ID is not set. " +
                    "The /embedded-signup/config endpoint will return an empty configId. " +
                    "Set this via Meta App Dashboard → WhatsApp → Configuration → Embedded Signup.");
        }

        log.info("Meta API configuration validated successfully. App ID: {}***",
                safePrefix(metaApiConfig.getAppId()));
    }

    private void validateRequired(String envVar, String configKey, String value) {
        if (isNullOrPlaceholder(value)) {
            String message = String.format(
                    "%n%n" +
                            "╔══════════════════════════════════════════════════════════════╗%n" +
                            "║  STARTUP FAILED — Missing Required Configuration             ║%n" +
                            "╠══════════════════════════════════════════════════════════════╣%n" +
                            "║  Config key : %-48s ║%n" +
                            "║  Env var    : %-48s ║%n" +
                            "║                                                              ║%n" +
                            "║  Set the environment variable before starting the service:   ║%n" +
                            "║  export %s=<your-value>%n" +
                            "╚══════════════════════════════════════════════════════════════╝%n",
                    configKey, envVar, envVar
            );
            throw new IllegalStateException(message);
        }
    }

    private boolean isNullOrPlaceholder(String value) {
        if (value == null) return true;
        return PLACEHOLDER_VALUES.contains(value.trim().toLowerCase())
                || PLACEHOLDER_VALUES.contains(value.trim());
    }

    private String safePrefix(String value) {
        if (value == null || value.length() < 4) return "****";
        return value.substring(0, 4);
    }
}