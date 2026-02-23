package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * Request DTO for verifying a phone number with OTP
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to verify a phone number")
public class VerifyPhoneNumberRequest {

    @NotNull(message = "Phone number ID is required")
    @Schema(description = "Phone number ID from database", example = "1")
    private String phoneNumberId;

    @NotBlank(message = "Verification code is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "Code must be 6 digits")
    @Schema(description = "6-digit verification code received via SMS/Voice", example = "456789")
    private String code;
}