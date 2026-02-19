package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request body for Meta Embedded Signup callback.
 *
 * Supports TWO flows:
 *   1. NORMAL SIGNUP: phoneNumberId is a real Meta phone number ID
 *   2. COEXISTENCE / APP_ONBOARDING: phoneNumberId is "NA" or null,
 *      signupType is "APP_ONBOARDING" — phone numbers are auto-discovered from WABA
 *
 * Flow:
 * 1. Frontend shows "Connect with WhatsApp Business" (Facebook Login SDK)
 * 2. User completes Meta's guided signup flow in popup
 * 3. Meta SDK calls your frontend callback with { code, wabaId, phoneNumberId, ... }
 * 4. Frontend sends THIS request to our backend
 * 5. Backend exchanges code → access token, creates WABA, registers phone, syncs numbers
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Meta Embedded Signup OAuth callback — sent by frontend after signup flow")
public class EmbeddedSignupCallbackRequest {

    @NotNull(message = "Organization ID is required")
    @Schema(description = "ID of the organization initiating signup", example = "1")
    private Long organizationId;

    @NotBlank(message = "OAuth authorization code is required")
    @Schema(
            description = "Short-lived OAuth code returned by Meta FB Login SDK after signup",
            example = "AQD8x7xxxxxxxxxxxxxxxxxxxxxx"
    )
    private String code;

    @Schema(
            description = "WABA ID returned by Meta FB SDK (if available). " +
                    "If not provided, backend auto-discovers from the access token.",
            example = "123456789012345"
    )
    private String wabaId;

    @Schema(
            description = "Phone number ID returned by Meta FB SDK. " +
                    "'NA' or null for coexistence/APP_ONBOARDING flow — backend auto-discovers.",
            example = "109876543210987"
    )
    private String phoneNumberId;

    @Schema(
            description = "Signup type from Meta SDK. 'APP_ONBOARDING' triggers coexistence flow " +
                    "with contact/history sync. Null or blank = normal embedded signup.",
            example = "APP_ONBOARDING"
    )
    private String signupType;

    @Schema(
            description = "Meta Business Manager ID (if returned by SDK)",
            example = "987654321098765"
    )
    private String businessManagerId;

    @Schema(
            description = "Timezone string returned by Meta SDK (optional)",
            example = "Asia/Kolkata"
    )
    private String timezone;

    // ════════════════════════════════════════════════════════════
    // Helper methods
    // ════════════════════════════════════════════════════════════

    /**
     * True if this is a coexistence / APP_ONBOARDING flow.
     * In this flow, the phone number ID is not provided by the SDK —
     * we must auto-discover it from the WABA's phone numbers list.
     */
    public boolean isCoexistenceFlow() {
        return "APP_ONBOARDING".equalsIgnoreCase(signupType)
                || phoneNumberId == null
                || "NA".equalsIgnoreCase(phoneNumberId)
                || phoneNumberId.isBlank();
    }

    /**
     * True if a valid phone number ID was provided by the frontend.
     */
    public boolean hasPhoneNumberId() {
        return phoneNumberId != null
                && !"NA".equalsIgnoreCase(phoneNumberId)
                && !phoneNumberId.isBlank();
    }
}