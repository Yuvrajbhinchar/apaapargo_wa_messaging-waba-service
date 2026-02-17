package com.aigreentick.services.wabaaccounts.constants;

/**
 * WhatsApp Messaging Limit Tiers
 */
public enum MessagingLimitTier {
    TIER_1K("TIER_1K", 1000),
    TIER_10K("TIER_10K", 10000),
    TIER_100K("TIER_100K", 100000),
    TIER_UNLIMITED("TIER_UNLIMITED", Integer.MAX_VALUE);

    private final String value;
    private final int limit;

    MessagingLimitTier(String value, int limit) {
        this.value = value;
        this.limit = limit;
    }

    public String getValue() {
        return value;
    }

    public int getLimit() {
        return limit;
    }
}