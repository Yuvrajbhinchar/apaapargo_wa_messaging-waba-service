package com.aigreentick.services.wabaaccounts.constants;
/**
 *
 * State machine:
 *
 *   (new) ──TX1──► PENDING
 *                    │
 *            Meta call (outside TX)
 *                    │
 *              ┌─────┴──────┐
 *           success       failure
 *              │               │
 *           ──TX2──         ──TX2──
 *           ACTIVE    REGISTRATION_FAILED
 *              │               │
 *           normal        retryable via
 *          operation      re-registration
 *
 * ACTIVE, BLOCKED, DISABLED are set by Meta webhooks
 * (account_update, phone_number_quality_update events).
 * ═══════════════════════════════════════════════════════════════
 */
public enum PhoneNumberStatus {

    /**
     * TX 1 committed: phone row exists in DB, Meta registration not yet attempted.
     * This is the crash-safe anchor. If the service dies between TX 1 and TX 2,
     * the phone stays PENDING and can be detected + retried on restart.
     */
    PENDING("pending"),

    /**
     * TX 2 committed after Meta success: phone is registered and operational.
     * Normal operating state — can send and receive messages.
     */
    ACTIVE("active"),

    /**
     * TX 2 committed after Meta failure: Meta rejected the registration call.
     * Safe to retry — Meta never registered this phone, so there is no
     * cross-system inconsistency. The caller can attempt registration again
     * with a corrected PIN or after resolving the Meta-side issue.
     */
    REGISTRATION_FAILED("registration_failed"),

    /**
     * Set by Meta webhook (account_update: BANNED).
     * Phone cannot send messages. Requires Meta intervention to lift.
     */
    BLOCKED("blocked"),

    /**
     * Set by Meta webhook or manual admin action.
     * Phone is deactivated on the platform side.
     */
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

    /** True if this status means the phone is usable for messaging. */
    public boolean isOperational() {
        return this == ACTIVE;
    }

    /** True if this status means registration can be retried. */
    public boolean isRetryable() {
        return this == PENDING || this == REGISTRATION_FAILED;
    }
}