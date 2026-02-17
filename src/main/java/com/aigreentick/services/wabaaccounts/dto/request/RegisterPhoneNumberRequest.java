package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * Request DTO for registering a new phone number
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to register a phone number")
public class RegisterPhoneNumberRequest {

    @NotNull(message = "WABA account ID is required")
    @Schema(description = "WABA account ID", example = "1")
    private Long wabaAccountId;

    @NotBlank(message = "Phone number ID is required")
    @Schema(description = "Phone number ID from Meta", example = "109876543210987")
    private String phoneNumberId;

    @NotBlank(message = "Two-step verification PIN is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "PIN must be 6 digits")
    @Schema(description = "6-digit PIN for two-step verification", example = "123456")
    private String pin;
}