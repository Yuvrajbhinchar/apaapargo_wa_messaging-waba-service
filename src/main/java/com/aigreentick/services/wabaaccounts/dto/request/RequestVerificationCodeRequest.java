package com.aigreentick.services.wabaaccounts.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * Request DTO for requesting verification code (SMS/Voice)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request verification code via SMS or Voice")
public class RequestVerificationCodeRequest {

    @NotNull(message = "Phone number ID is required")
    @Schema(description = "Phone number ID from database", example = "1")
    private Long phoneNumberId;

    @NotBlank(message = "Verification method is required")
    @Pattern(regexp = "^(SMS|VOICE)$", message = "Method must be SMS or VOICE")
    @Schema(description = "Verification method", example = "SMS", allowableValues = {"SMS", "VOICE"})
    private String method;

    @Schema(description = "Language/locale for verification message", example = "en_US")
    @Builder.Default
    private String locale = "en_US";
}