package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * Request DTO for updating WABA status
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to update WABA status")
public class UpdateWabaStatusRequest {

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "^(active|suspended|disconnected)$",
            message = "Status must be active, suspended, or disconnected")
    @Schema(description = "New status for WABA",
            example = "active",
            allowableValues = {"active", "suspended", "disconnected"})
    private String status;

    @Schema(description = "Reason for status change", example = "Violated WhatsApp policies")
    private String reason;
}