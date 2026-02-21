package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.dto.response.SystemUserProvisioningResponse;
import com.aigreentick.services.wabaaccounts.service.SystemUserProvisioningService;
import com.aigreentick.services.wabaaccounts.service.SystemUserProvisioningService.BulkProvisioningResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system-users")
@RequiredArgsConstructor
@Slf4j
public class SystemUserProvisioningController {

    private final SystemUserProvisioningService provisioningService;

    /**
     * Provision a system user for an organization (or re-provision).
     *
     * POST /api/v1/system-users/provision/{organizationId}
     *
     * Flow:
     *   1. Create system user in the org's Business Manager
     *   2. Assign all org's WABAs to the system user (MANAGE permission)
     *   3. Generate permanent access token
     *   4. Replace 60-day user token with permanent system user token in DB
     *
     * Query params:
     *   forceRefresh=true  → re-provision even if system user token already exists
     *                        (use when: system user was deleted, token was revoked,
     *                         or you want to regenerate for security reasons)
     *
     * Example:
     *   POST /api/v1/system-users/provision/42
     *   POST /api/v1/system-users/provision/42?forceRefresh=true
     *
     * Responses:
     *   200 OK    → provisioning succeeded (or was already done)
     *   400 Bad   → Phase 1 not complete, no WABAs found, BM permission issue
     *   500 Error → unexpected failure
     */
    @PostMapping("/provision/{organizationId}")
    public ResponseEntity<SystemUserProvisioningResponse> provision(
            @PathVariable Long organizationId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("Manual Phase 2 provisioning triggered: orgId={}, forceRefresh={}",
                organizationId, forceRefresh);

        SystemUserProvisioningResponse response =
                provisioningService.provisionForOrganization(organizationId, forceRefresh);

        return ResponseEntity.ok(response);
    }

    /**
     * Check Phase 2 provisioning status for an organization.
     *
     * GET /api/v1/system-users/status/{organizationId}
     *
     * Returns whether the org has a permanent system user token or still
     * relies on a 60-day user token.
     *
     * Example:
     *   GET /api/v1/system-users/status/42
     */
    @GetMapping("/status/{organizationId}")
    public ResponseEntity<ProvisioningStatusResponse> status(
            @PathVariable Long organizationId) {

        log.debug("Checking Phase 2 status for orgId={}", organizationId);

        boolean hasSystemUser = provisioningService.hasSystemUserToken(organizationId);

        return ResponseEntity.ok(ProvisioningStatusResponse.builder()
                .organizationId(organizationId)
                .phase2Complete(hasSystemUser)
                .tokenType(hasSystemUser ? "SYSTEM_USER (permanent)" : "USER_TOKEN (60-day)")
                .message(hasSystemUser
                        ? "Organization has a permanent system user token. No action needed."
                        : "Organization is using a 60-day user token. " +
                        "POST /api/v1/system-users/provision/" + organizationId + " to upgrade.")
                .build());
    }

    /**
     * Bulk provision: upgrade all orgs still using 60-day user tokens.
     *
     * POST /api/v1/system-users/provision-all
     *
     * Useful for:
     *   - Initial rollout of Phase 2 to existing customers
     *   - Running as a scheduled job before tokens expire
     *
     * Note: This can take time if there are many orgs. Consider running
     * it asynchronously or as a batch job in production.
     */
    @PostMapping("/provision-all")
    public ResponseEntity<BulkProvisioningResponse> provisionAll() {
        log.info("Bulk Phase 2 provisioning triggered for all orgs with user tokens");

        BulkProvisioningResult result = provisioningService.provisionAllPendingOrgs();

        return ResponseEntity.ok(BulkProvisioningResponse.builder()
                .totalAttempted(result.attempted())
                .succeeded(result.succeeded())
                .failed(result.failed())
                .message(String.format(
                        "Bulk provisioning complete: %d succeeded, %d failed out of %d orgs.",
                        result.succeeded(), result.failed(), result.attempted()))
                .build());
    }

    // ──────────────────────────────────────────────────────────
    // Inner response DTOs (simple, controller-scoped)
    // ──────────────────────────────────────────────────────────

    @lombok.Builder
    @lombok.Data
    public static class ProvisioningStatusResponse {
        private Long organizationId;
        private boolean phase2Complete;
        private String tokenType;
        private String message;
    }

    @lombok.Builder
    @lombok.Data
    public static class BulkProvisioningResponse {
        private int totalAttempted;
        private int succeeded;
        private int failed;
        private String message;
    }
}