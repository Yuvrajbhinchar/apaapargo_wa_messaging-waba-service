package com.aigreentick.services.wabaaccounts.constants;

/**
 * Application-wide constants for WABA service
 */
public final class WabaConstants {

    private WabaConstants() {
        throw new IllegalStateException("Constants class cannot be instantiated");
    }

    // API Versioning
    public static final String API_V1 = "/api/v1";

    // Meta API Constants
    public static final String META_GRAPH_API_VERSION = "v21.0";
    public static final String META_MESSAGING_PRODUCT = "whatsapp";

    // WABA Limits
    public static final int MAX_PHONE_NUMBERS_PER_WABA = 20;
    public static final int MAX_WABAS_UNVERIFIED = 2;
    public static final int MAX_WABAS_VERIFIED = 20;

    // Verification
    public static final String VERIFICATION_METHOD_SMS = "SMS";
    public static final String VERIFICATION_METHOD_VOICE = "VOICE";
    public static final String DEFAULT_VERIFICATION_LOCALE = "en_US";

    // Token Expiry (in days)
    public static final int ACCESS_TOKEN_EXPIRY_DAYS = 60;

    // Error Messages
    public static final String ERROR_WABA_NOT_FOUND = "WABA account not found";
    public static final String ERROR_PHONE_NOT_FOUND = "Phone number not found";
    public static final String ERROR_INVALID_VERIFICATION_CODE = "Invalid verification code";
    public static final String ERROR_META_API_FAILED = "Meta API request failed";
    public static final String ERROR_DUPLICATE_WABA = "WABA already exists for this organization";

    // Success Messages
    public static final String SUCCESS_WABA_CREATED = "WABA account created successfully";
    public static final String SUCCESS_PHONE_VERIFIED = "Phone number verified successfully";
    public static final String SUCCESS_WEBHOOK_VERIFIED = "Webhook verified successfully";
}