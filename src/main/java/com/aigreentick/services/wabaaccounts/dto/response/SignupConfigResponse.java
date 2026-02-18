package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Response DTO for the embedded signup SDK configuration.
 *
 * Frontend uses this to initialize the Facebook Login SDK before
 * showing the "Connect WhatsApp Business" button.
 *
 * Usage:
 * <pre>
 * const config = await fetch('/api/v1/embedded-signup/config').then(r => r.json());
 * FB.init({ appId: config.data.metaAppId, version: config.data.apiVersion });
 *
 * FB.login(callback, {
 *   config_id: config.data.configId,           // your embedded signup config ID
 *   response_type: 'code',
 *   override_default_response_type: true,
 *   extras: JSON.parse(config.data.extrasJson)
 * });
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Meta SDK configuration for initializing the WhatsApp Embedded Signup flow on the frontend")
public class SignupConfigResponse {

    @Schema(
            description = "Meta App ID — used to initialize FB.init()",
            example = "1234567890"
    )
    private String metaAppId;

    @Schema(
            description = "Meta Graph API version",
            example = "v21.0"
    )
    private String apiVersion;

    @Schema(
            description = "Required OAuth scopes (comma-separated)",
            example = "whatsapp_business_management,whatsapp_business_messaging,business_management"
    )
    private String scopes;

    @Schema(
            description = "JSON string of extras to pass into FB.login() config",
            example = "{\"feature\": \"whatsapp_embedded_signup\", \"version\": 2, \"sessionInfoVersion\": \"3\"}"
    )
    private String extrasJson;

    @Schema(
            description = "Backend endpoint where frontend should POST the OAuth code",
            example = "/api/v1/embedded-signup/callback"
    )
    private String callbackEndpoint;

    @Schema(
            description = "Your Embedded Signup Config ID from Meta (set in Meta App Dashboard → WhatsApp → Configuration)",
            example = "your-embedded-signup-config-id",
            nullable = true
    )
    private String configId;
}