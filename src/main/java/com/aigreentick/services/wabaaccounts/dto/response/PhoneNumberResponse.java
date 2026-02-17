package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Response DTO for phone number
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Phone number details")
public class PhoneNumberResponse {

    @Schema(description = "Phone number database ID", example = "1")
    private Long id;

    @Schema(description = "WABA account ID", example = "1")
    private Long wabaAccountId;

    @Schema(description = "Phone number ID from Meta", example = "109876543210987")
    private String phoneNumberId;

    @Schema(description = "Display phone number", example = "+1234567890")
    private String displayPhoneNumber;

    @Schema(description = "Phone number status", example = "active")
    private String status;

    @Schema(description = "Verified business name", example = "AiGreenTick Inc")
    private String verifiedName;

    @Schema(description = "Quality rating", example = "GREEN")
    private String qualityRating;

    @Schema(description = "Messaging limit tier", example = "TIER_10K")
    private String messagingLimitTier;

    @Schema(description = "Daily messaging limit", example = "10000")
    private Integer dailyLimit;

    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;
}