package com.aigreentick.services.wabaaccounts.exception;

import lombok.Getter;

/**
 * Thrown when Meta Graph API returns an error or is unavailable
 *
 * Has two types:
 * - MetaApiException: server errors (5xx), retryable
 * - MetaApiException.ClientException: client errors (4xx), NOT retried
 */
@Getter
public class MetaApiException extends WabaServiceException {

    private final int httpStatus;

    public MetaApiException(String message) {
        super(message, "META_API_ERROR");
        this.httpStatus = 503;
    }

    public MetaApiException(String message, int httpStatus) {
        super(message, "META_API_ERROR");
        this.httpStatus = httpStatus;
    }

    public MetaApiException(String message, Throwable cause) {
        super(message, "META_API_ERROR", cause);
        this.httpStatus = 503;
    }

    public static MetaApiException serviceUnavailable() {
        return new MetaApiException(
                "Meta API is temporarily unavailable. Please try again later."
        );
    }

    public static MetaApiException unauthorized() {
        return new ClientException(
                "Meta API authentication failed. Access token may be expired.", 401
        );
    }

    // ========================
    // INNER CLASS
    // 4xx errors — do NOT retry
    // ========================

    /**
     * Represents a 4xx client error from Meta API
     * These should NOT be retried (bad request, invalid token, etc.)
     */
    @Getter
    public static class ClientException extends MetaApiException {

        public ClientException(String message, int httpStatus) {
            super(message, httpStatus);
        }
    }
}