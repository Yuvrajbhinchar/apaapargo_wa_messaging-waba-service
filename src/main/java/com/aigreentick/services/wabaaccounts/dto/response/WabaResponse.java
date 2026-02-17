package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for WABA account
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "WABA account details")
public class WabaResponse {

    @Schema(description = "WABA database ID", example = "1")
    private Long id;

    @Schema(description = "Organization ID", example = "1")
    private Long organizationId;

    @Schema(description = "WhatsApp Business Account ID", example = "123456789012345")
    private String wabaId;

    @Schema(description = "WABA status", example = "active")
    private String status;

    @Schema(description = "Number of phone numbers", example = "2")
    private Integer phoneNumberCount;

    @Schema(description = "List of phone numbers")
    private List<PhoneNumberResponse> phoneNumbers;

    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;
}