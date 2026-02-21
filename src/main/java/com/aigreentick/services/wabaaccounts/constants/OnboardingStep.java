// ═══════════════════════════════════════════════════════════════════
        // FILE 1: OnboardingStep.java — Step enum for tracking progress
// ═══════════════════════════════════════════════════════════════════
        package com.aigreentick.services.wabaaccounts.constants;

/**
 * Discrete steps in the embedded signup saga.
 * Order matters — steps execute sequentially.
 * Each step is independently retryable if it hasn't completed yet.
 */
public enum OnboardingStep {
    TOKEN_EXCHANGE,          // Step A: OAuth code → short-lived token
    TOKEN_EXTENSION,         // Step B: short-lived → long-lived token
    SCOPE_VERIFICATION,      // Step C: verify required permissions
    WABA_RESOLUTION,         // Step D: resolve + verify WABA ownership
    BM_RESOLUTION,           // Step E: resolve Business Manager ID
    PHONE_RESOLUTION,        // Step F: resolve phone number ID
    CREDENTIAL_PERSISTENCE,  // Step G: save OAuth + WABA to DB
    WEBHOOK_SUBSCRIBE,       // Step G2: subscribe app to WABA webhooks
    PHONE_SYNC,              // Step G3: sync phone numbers from Meta
    PHONE_REGISTRATION,      // Step H: register phone with Meta
    SMB_SYNC,                // Step I: coexistence contact + history sync
    PHASE2_PROVISIONING;     // Step J: system user provisioning

    /**
     * True if this step makes an irreversible external call
     * whose result MUST be persisted before continuing.
     */
    public boolean requiresImmediatePersistence() {
        return this == TOKEN_EXCHANGE || this == TOKEN_EXTENSION;
    }
}
