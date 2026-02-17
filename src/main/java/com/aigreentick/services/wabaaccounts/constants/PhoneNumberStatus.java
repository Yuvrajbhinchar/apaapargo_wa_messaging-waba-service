package com.aigreentick.services.wabaaccounts.constants;

/**
 * Phone Number Status
 */
public enum PhoneNumberStatus {
    ACTIVE("active"),
    BLOCKED("blocked"),
    DISABLED("disabled");

    private final String value;

    PhoneNumberStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static PhoneNumberStatus fromValue(String value) {
        for (PhoneNumberStatus status : PhoneNumberStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid phone status: " + value);
    }
}