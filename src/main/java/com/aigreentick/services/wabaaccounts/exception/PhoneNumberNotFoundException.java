package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown when phone number is not found
 */
public class PhoneNumberNotFoundException extends WabaServiceException {

    public PhoneNumberNotFoundException(String message) {
        super(message, "PHONE_NUMBER_NOT_FOUND");
    }

    public static PhoneNumberNotFoundException withId(Long id) {
        return new PhoneNumberNotFoundException("Phone number not found with ID: " + id);
    }

    public static PhoneNumberNotFoundException withPhoneNumberId(String phoneNumberId) {
        return new PhoneNumberNotFoundException("Phone number not found: " + phoneNumberId);
    }
}