package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Internal credentials response — consumed by campaign-service and other
 * internal services that need to make Meta API calls on behalf of a WABA.
 *
 * Contract:
 *   - phoneNumberId : Meta phone number ID (e.g. "109876543210987")
 *   - accessToken   : decrypted, ready-to-use Meta access token
 *   - All fields are always present — never null on a 200 response
 *
 * ⚠️  INTERNAL ONLY — never expose this endpoint publicly.
 *     The access token is returned in plaintext; mTLS / VPC boundary is
 *     the only protection between this service and its callers.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Meta credentials for a WABA account — internal use only")
public class WabaCredentialsResponse {

    @Schema(description = "Internal WABA account DB ID", example = "7001")
    private Long wabaAccountId;

    /**
     * Meta phone number ID — used as the 'from' field when sending messages.
     * This is Meta's opaque numeric ID, NOT the human-readable phone number.
     * Example: "109876543210987"
     */
    @Schema(description = "Meta phone number ID", example = "123456789012345")
    private String phoneNumberId;

    /**
     * Decrypted Meta access token — ready for use in Authorization headers.
     * Stored encrypted in DB; decrypted here on the way out.
     * Do NOT log this field.
     */
    @Schema(description = "Decrypted Meta access token", example = "EAABwzLixnjYBO...")
    private String accessToken;
}