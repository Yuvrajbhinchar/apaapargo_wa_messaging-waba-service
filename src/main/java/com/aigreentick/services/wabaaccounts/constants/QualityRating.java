package com.aigreentick.services.wabaaccounts.constants;

/**
 * WhatsApp Phone Number Quality Rating
 * Determines messaging limits
 */
public enum QualityRating {
    GREEN("GREEN"),     // High quality
    YELLOW("YELLOW"),   // Medium quality
    RED("RED"),         // Low quality - risk of ban
    UNKNOWN("UNKNOWN");

    private final String value;

    QualityRating(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}