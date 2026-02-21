package com.aigreentick.services.wabaaccounts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "meta")
@Data
public class MetaApiConfig {

    private String appId;
    private String appSecret;
    private String graphApiVersion = "v23.0";
    private String graphApiBaseUrl = "https://graph.facebook.com";
    private String webhookVerifyToken;
    private String embeddedSignupConfigId;

    /**
     * Separate version for webhook subscribe + phone register.
     * Meta requires v18.0 for these two endpoints.
     * Bound from: meta.phone-registration-api-version
     */
    private String phoneRegistrationApiVersion = "v18.0";

    /** Default PIN for phone registration during embedded signup */
    private String defaultRegistrationPin = "123456";

    /** Primary versioned URL: https://graph.facebook.com/v23.0 */
    public String getVersionedBaseUrl() {
        return graphApiBaseUrl + "/" + graphApiVersion;
    }

    /**
     * Version-specific URL builder.
     * Use for endpoints that require a different Graph API version.
     */
    public String getVersionedBaseUrl(String version) {
        return graphApiBaseUrl + "/" + version;
    }

    /** URL for v18.0 endpoints (subscribe webhook, register phone) */
    public String getRegistrationVersionedBaseUrl() {
        return graphApiBaseUrl + "/" + phoneRegistrationApiVersion;
    }

    public String getApiVersion() {
        return graphApiVersion;
    }
}