package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request DTO for creating a new WABA account
 * Used after Meta embedded signup flow
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create a new WABA account")
public class CreateWabaRequest {

    @NotNull(message = "Organization ID is required")
    @Schema(description = "ID of the organization", example = "1")
    private Long organizationId;

    @NotBlank(message = "WABA ID is required")
    @Schema(description = "WhatsApp Business Account ID from Meta", example = "123456789012345")
    private String wabaId;

    @NotBlank(message = "Business Manager ID is required")
    @Schema(description = "Meta Business Manager ID", example = "987654321098765")
    private String businessManagerId;

    @NotBlank(message = "Access token is required")
    @Schema(description = "OAuth access token from Meta", example = "EAABxxxxxxxx")
    private String accessToken;

    @Schema(description = "Token expiry in seconds", example = "5184000")
    private Long expiresIn;
}