package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Response for Phase 2 — System User Provisioning.
 *
 * Returned by:
 *   - POST /api/v1/system-users/provision/{organizationId}  (manual trigger)
 *   - Auto-provisioning after embedded signup
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemUserProvisioningResponse {

    /** Whether provisioning succeeded */
    private boolean success;

    /** The organization this provisioning was performed for */
    private Long organizationId;

    /** The Meta system user ID created or reused */
    private String systemUserId;

    /** Number of WABAs successfully assigned to the system user */
    private Integer wabasAssigned;

    /** Total number of WABAs the org has */
    private Integer wabasTotal;

    /** Human-readable status message */
    private String message;

    /**
     * Factory: provisioning completed successfully
     */
    public static SystemUserProvisioningResponse success(Long organizationId,
                                                         String systemUserId,
                                                         int wabasAssigned,
                                                         int wabasTotal) {
        return SystemUserProvisioningResponse.builder()
                .success(true)
                .organizationId(organizationId)
                .systemUserId(systemUserId)
                .wabasAssigned(wabasAssigned)
                .wabasTotal(wabasTotal)
                .message(String.format(
                        "System user provisioned. Permanent token active. %d/%d WABAs assigned.",
                        wabasAssigned, wabasTotal))
                .build();
    }

    /**
     * Factory: org was already provisioned, nothing to do
     */
    public static SystemUserProvisioningResponse alreadyProvisioned(Long organizationId,
                                                                    String systemUserId) {
        return SystemUserProvisioningResponse.builder()
                .success(true)
                .organizationId(organizationId)
                .systemUserId(systemUserId)
                .message("Organization already has a permanent system user token. No action taken.")
                .build();
    }

    /**
     * Factory: provisioning failed
     */
    public static SystemUserProvisioningResponse failed(Long organizationId, String reason) {
        return SystemUserProvisioningResponse.builder()
                .success(false)
                .organizationId(organizationId)
                .message("Provisioning failed: " + reason)
                .build();
    }
}