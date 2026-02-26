package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Health check response for a WABA integration.
 *
 * Returned by GET /embedded-signup/health/{organizationId}
 *
 * Each sub-check runs independently — a failure in one does NOT prevent others.
 * The overall_status is degraded if ANY check fails, unhealthy if token is invalid.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Health status of a WABA integration for an organization")
public class WabaHealthResponse {

    // ── Overall ───────────────────────────────────────────────────────────────

    @Schema(description = "Organization ID checked", example = "42")
    private Long organizationId;

    @Schema(description = "Overall integration health", example = "HEALTHY",
            allowableValues = {"HEALTHY", "DEGRADED", "UNHEALTHY", "UNKNOWN"})
    private String overallStatus;

    @Schema(description = "Summary message", example = "All checks passed")
    private String summary;

    @Schema(description = "Timestamp of this health check")
    private LocalDateTime checkedAt;

    // ── Token ─────────────────────────────────────────────────────────────────

    @Schema(description = "Token validity result")
    private TokenHealth tokenHealth;

    // ── WABA accounts ─────────────────────────────────────────────────────────

    @Schema(description = "Health of each WABA account")
    private List<WabaCheck> wabaChecks;

    // ── Phone numbers ─────────────────────────────────────────────────────────

    @Schema(description = "Health of each phone number")
    private List<PhoneCheck> phoneChecks;

    // ════════════════════════════════════════════════════════════════════════
    // NESTED TYPES
    // ════════════════════════════════════════════════════════════════════════

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Token validity health")
    public static class TokenHealth {

        @Schema(description = "Is the token currently valid on Meta?", example = "true")
        private Boolean isValid;

        @Schema(description = "Token type (USER or SYSTEM_USER)", example = "SYSTEM_USER")
        private String tokenType;

        @Schema(description = "Days until token expires (null = permanent)", example = "45")
        private Long daysUntilExpiry;

        @Schema(description = "Whether Phase 2 (system user) provisioning is complete", example = "true")
        private Boolean phase2Complete;

        @Schema(description = "Scopes granted on the token")
        private List<String> grantedScopes;

        @Schema(description = "Required scopes that are missing (should be empty)")
        private List<String> missingScopes;

        @Schema(description = "Token check status", example = "OK",
                allowableValues = {"OK", "EXPIRING_SOON", "EXPIRED", "INVALID", "CHECK_FAILED"})
        private String status;

        @Schema(description = "Detail about the token check result")
        private String detail;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Health check result for a single WABA account")
    public static class WabaCheck {

        @Schema(description = "WABA ID on Meta", example = "123456789012345")
        private String wabaId;

        @Schema(description = "Internal DB ID", example = "1")
        private Long wabaAccountId;

        @Schema(description = "WABA review status from Meta", example = "APPROVED",
                allowableValues = {"APPROVED", "PENDING", "REJECTED", "UNKNOWN"})
        private String reviewStatus;

        @Schema(description = "WABA status in local DB", example = "active")
        private String localStatus;

        @Schema(description = "Message template namespace (present = WABA operational)", example = "abcd_namespace")
        private String templateNamespace;

        @Schema(description = "WABA check status", example = "OK",
                allowableValues = {"OK", "REVIEW_PENDING", "REJECTED", "CHECK_FAILED"})
        private String status;

        @Schema(description = "Detail about the WABA check result")
        private String detail;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Health check result for a single phone number")
    public static class PhoneCheck {

        @Schema(description = "Meta phone number ID", example = "109876543210987")
        private String phoneNumberId;

        @Schema(description = "Display phone number", example = "+91 98765 43210")
        private String displayPhoneNumber;

        @Schema(description = "Phone status on Meta", example = "CONNECTED",
                allowableValues = {"CONNECTED", "DISCONNECTED", "FLAGGED", "RESTRICTED", "UNKNOWN"})
        private String metaStatus;

        @Schema(description = "Quality rating from Meta", example = "GREEN",
                allowableValues = {"GREEN", "YELLOW", "RED", "UNKNOWN"})
        private String qualityRating;

        @Schema(description = "Verified business name on Meta", example = "AiGreenTick Inc")
        private String verifiedName;

        @Schema(description = "Whether business profile exists on Meta", example = "true")
        private Boolean profileExists;

        @Schema(description = "Phone check status", example = "OK",
                allowableValues = {"OK", "QUALITY_WARNING", "RESTRICTED", "DISCONNECTED", "CHECK_FAILED"})
        private String status;

        @Schema(description = "Detail about the phone check result")
        private String detail;
    }

    // ════════════════════════════════════════════════════════════════════════
    // STATUS CONSTANTS
    // ════════════════════════════════════════════════════════════════════════

    public static final String STATUS_HEALTHY   = "HEALTHY";
    public static final String STATUS_DEGRADED  = "DEGRADED";   // Some checks failed, messaging may still work
    public static final String STATUS_UNHEALTHY = "UNHEALTHY";  // Token invalid — messaging is broken NOW
    public static final String STATUS_UNKNOWN   = "UNKNOWN";    // Could not determine status
}