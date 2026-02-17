package com.aigreentick.services.wabaaccounts.exception;

/**
 * Thrown when webhook verification fails
 */
public class WebhookVerificationException extends WabaServiceException {

    public WebhookVerificationException(String message) {
        super(message, "WEBHOOK_VERIFICATION_FAILED");
    }
}