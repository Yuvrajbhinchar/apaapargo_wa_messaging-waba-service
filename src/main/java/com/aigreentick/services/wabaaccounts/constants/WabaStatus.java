package com.aigreentick.services.wabaaccounts.constants;

/**
 * WABA Account Status
 */
public enum WabaStatus {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DISCONNECTED("disconnected");

    private final String value;

    WabaStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static WabaStatus fromValue(String value) {
        for (WabaStatus status : WabaStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid WABA status: " + value);
    }
}