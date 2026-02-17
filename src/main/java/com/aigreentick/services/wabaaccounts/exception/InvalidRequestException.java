package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown for invalid business logic requests
 */
public class InvalidRequestException extends WabaServiceException {

    public InvalidRequestException(String message) {
        super(message, "INVALID_REQUEST");
    }

    public static InvalidRequestException maxPhoneNumbersReached(int max) {
        return new InvalidRequestException(
                "Maximum phone numbers per WABA reached. Limit is " + max
        );
    }

    public static InvalidRequestException wabaNotActive(Long wabaId) {
        return new InvalidRequestException(
                "WABA account " + wabaId + " is not active"
        );
    }
}