package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request body for Meta Embedded Signup callback.
 *
 * Flow:
 * 1. Frontend shows "Connect with WhatsApp Business" (Facebook Login SDK)
 * 2. User completes Meta's guided signup flow in popup
 * 3. Meta SDK calls your frontend callback with { code, wabaId, ... }
 * 4. Frontend sends THIS request to our backend
 * 5. Backend exchanges code → access token, creates WABA, syncs phone numbers
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
            description = "Meta Business Manager ID (if returned by SDK)",
            example = "987654321098765"
    )
    private String businessManagerId;

    @Schema(
            description = "Timezone string returned by Meta SDK (optional)",
            example = "Asia/Kolkata"
    )
    private String timezone;
}