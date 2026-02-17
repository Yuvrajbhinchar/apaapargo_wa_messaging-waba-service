package com.aigreentick.services.wabaaccounts.exception;

import lombok.Getter;

/**
 * Thrown when Meta Graph API returns error or is unavailable
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
        return new MetaApiException("Meta API is temporarily unavailable. Please try again later.");
    }

    public static MetaApiException unauthorized() {
        return new MetaApiException("Meta API authentication failed. Access token may be expired.", 401);
    }
}