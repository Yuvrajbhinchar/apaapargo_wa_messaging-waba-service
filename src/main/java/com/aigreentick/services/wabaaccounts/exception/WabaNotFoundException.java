package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown when WABA account is not found
 */
public class WabaNotFoundException extends WabaServiceException {

    public WabaNotFoundException(String message) {
        super(message, "WABA_NOT_FOUND");
    }

    public static WabaNotFoundException withId(Long id) {
        return new WabaNotFoundException("WABA account not found with ID: " + id);
    }

    public static WabaNotFoundException withWabaId(String wabaId) {
        return new WabaNotFoundException("WABA account not found with WABA ID: " + wabaId);
    }
}