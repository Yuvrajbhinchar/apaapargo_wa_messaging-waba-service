package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

/**
 * Response returned after a successful Meta Embedded Signup flow.
 * Frontend uses this to show the connected WABA and phone numbers.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Result of completing Meta Embedded Signup flow")
public class EmbeddedSignupResponse {

    @Schema(description = "WABA database ID", example = "1")
    private Long wabaAccountId;

    @Schema(description = "Meta WABA ID", example = "123456789012345")
    private String wabaId;

    @Schema(description = "WABA account status", example = "ACTIVE")
    private String status;

    @Schema(description = "Business Manager ID", example = "987654321098765")
    private String businessManagerId;

    @Schema(description = "Token expiry in seconds from now", example = "5183944")
    private Long tokenExpiresIn;

    @Schema(description = "Whether token is long-lived (60 days)", example = "true")
    private Boolean longLivedToken;

    @Schema(description = "Phone numbers found under this WABA")
    private List<PhoneNumberResponse> phoneNumbers;

    @Schema(description = "Number of phone numbers discovered", example = "2")
    private Integer phoneNumberCount;

    @Schema(description = "Whether webhook subscription succeeded", example = "true")
    private Boolean webhookSubscribed;

    @Schema(description = "Human-readable summary for the UI")
    private String summary;
}