package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown when WABA already exists for an organization
 */
public class DuplicateWabaException extends WabaServiceException {

    public DuplicateWabaException(String message) {
        super(message, "DUPLICATE_WABA");
    }

    public static DuplicateWabaException forOrganization(Long orgId, String wabaId) {
        return new DuplicateWabaException(
                "WABA " + wabaId + " already connected to organization " + orgId
        );
    }
}