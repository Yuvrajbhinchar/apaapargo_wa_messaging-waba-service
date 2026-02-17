package com.aigreentick.services.wabaaccounts.exception;

import lombok.Getter;

/**
 * Base exception for all WABA service exceptions
 */
@Getter
public class WabaServiceException extends RuntimeException {

    private final String errorCode;

    public WabaServiceException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public WabaServiceException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}