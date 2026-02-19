package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.client.MetaApiRetryExecutor;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ══════════════════════════════════════════════════════════════════
 * Phone Registration Service
 * ══════════════════════════════════════════════════════════════════
 *
 * Handles the missing Meta onboarding steps identified in PHP→Java migration:
 *
 *   1. Phone number registration  (POST /{phoneNumberId}/register)
 *   2. Phone number auto-discovery for coexistence flow
 *   3. SMB contact + history sync for coexistence/APP_ONBOARDING
 *
 * WHY THIS IS SEPARATE FROM PhoneNumberService
 * ──────────────────────────────────────────────
 * PhoneNumberService handles CRUD + manual registration via REST API.
 * This service handles the AUTOMATED registration during embedded signup —
 * different flow, different error handling, different retry strategy.
 *
 * During onboarding, phone registration is best-effort:
 *   - If it fails, the WABA is still connected (phone can be registered later)
 *   - We don't want registration failure to block the entire signup
 *
 * In the manual PhoneNumberService flow, registration is required:
 *   - User explicitly asked to register, failure must be reported
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneRegistrationService {

    private final MetaApiClient metaApiClient;
    private final MetaApiRetryExecutor metaRetry;

    /**
     * Default 2-step verification PIN for phone registration.
     * In production, this should come from the frontend or be configurable per-org.
     * The PHP controller hardcodes '123456' — we keep the same default but make it configurable.
     */
    @Value("${meta.default-registration-pin:123456}")
    private String defaultRegistrationPin;

    // ════════════════════════════════════════════════════════════
    // 1. REGISTER PHONE NUMBER
    // ════════════════════════════════════════════════════════════

    /**
     * Register a phone number with Meta Cloud API during embedded signup.
     *
     * This is the equivalent of the PHP:
     *   POST /{phone_number_id}/register
     *   body: { messaging_product: "whatsapp", pin: "123456" }
     *
     * Without this call, the phone number remains inactive and
     * cannot send or receive messages.
     *
     * @param phoneNumberId  Meta phone number ID to register
     * @param accessToken    Valid access token with whatsapp_business_messaging scope
     * @return true if registration succeeded, false if it failed (best-effort)
     */
    public boolean registerPhoneNumber(String phoneNumberId, String accessToken) {
        return registerPhoneNumber(phoneNumberId, accessToken, defaultRegistrationPin);
    }

    /**
     * Register with a custom PIN (for orgs that have set their own 2-step PIN).
     */
    public boolean registerPhoneNumber(String phoneNumberId, String accessToken, String pin) {
        try {
            log.info("Registering phone number during onboarding: phoneNumberId={}", phoneNumberId);

            MetaApiResponse response = metaRetry.executeWithContext(
                    "registerPhone", phoneNumberId,
                    () -> metaApiClient.registerPhoneNumber(phoneNumberId, accessToken, pin));

            if (response.isOk()) {
                log.info("Phone number registered successfully: phoneNumberId={}", phoneNumberId);
                return true;
            }

            // Registration can fail if phone is already registered (not an error)
            String errorMsg = response.getErrorMessage();
            if (errorMsg != null && errorMsg.contains("already registered")) {
                log.info("Phone already registered (OK): phoneNumberId={}", phoneNumberId);
                return true;
            }

            log.warn("Phone registration returned error: phoneNumberId={}, error={}",
                    phoneNumberId, errorMsg);
            return false;

        } catch (MetaApiException ex) {
            // Don't let registration failure block onboarding
            log.warn("Phone registration failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    // 2. DISCOVER PHONE NUMBER (Coexistence flow)
    // ════════════════════════════════════════════════════════════

    /**
     * Auto-discover the latest phone number for a WABA.
     *
     * Used in the coexistence / APP_ONBOARDING flow where the frontend
     * sends phoneNumberId = "NA" because the phone number was added
     * directly in Meta Business Suite (not via the embedded signup SDK).
     *
     * Equivalent of PHP:
     *   GET /{waba_id}/phone_numbers → pick last (latest) phone
     *
     * @param wabaId       WABA ID to fetch phone numbers for
     * @param accessToken  Valid access token
     * @return DiscoveredPhone with ID and display number, or null if none found
     */
    @SuppressWarnings("unchecked")
    public DiscoveredPhone discoverLatestPhoneNumber(String wabaId, String accessToken) {
        try {
            log.info("Discovering phone numbers for WABA: wabaId={}", wabaId);

            MetaApiResponse response = metaRetry.executeWithContext(
                    "discoverPhones", wabaId,
                    () -> metaApiClient.getPhoneNumbers(wabaId, accessToken));

            if (!response.isOk() || response.getData() == null) {
                log.warn("Failed to fetch phone numbers for WABA: wabaId={}", wabaId);
                return null;
            }

            Object dataObj = response.getData().get("data");
            if (!(dataObj instanceof List)) {
                // Some endpoints return data directly without nesting
                dataObj = response.getData();
            }

            List<Map<String, Object>> numbers;
            if (dataObj instanceof List) {
                numbers = (List<Map<String, Object>>) dataObj;
            } else {
                log.warn("Unexpected phone numbers response format for WABA: {}", wabaId);
                return null;
            }

            if (numbers.isEmpty()) {
                log.warn("No phone numbers found for WABA: wabaId={}", wabaId);
                return null;
            }

            // Pick the LATEST added phone number (last in array, same as PHP: end($numbers))
            Map<String, Object> latestPhone = numbers.get(numbers.size() - 1);
            String phoneId = String.valueOf(latestPhone.get("id"));
            String displayNumber = latestPhone.get("display_phone_number") != null
                    ? String.valueOf(latestPhone.get("display_phone_number"))
                    : null;

            log.info("Discovered latest phone: phoneNumberId={}, display={}",
                    phoneId, displayNumber);

            return new DiscoveredPhone(phoneId, displayNumber);

        } catch (Exception ex) {
            log.error("Phone discovery failed for WABA {}: {}", wabaId, ex.getMessage());
            return null;
        }
    }

    /**
     * Discovered phone number from Meta API.
     */
    public record DiscoveredPhone(String phoneNumberId, String displayPhoneNumber) {

        /**
         * Clean the display phone number by removing spaces and '+' signs.
         * Equivalent of PHP: preg_replace('/[\s+]+/', '', $display_phone_number)
         */
        public String cleanedPhoneNumber() {
            if (displayPhoneNumber == null) return null;
            return displayPhoneNumber.replaceAll("[\\s+]+", "");
        }
    }

    // ════════════════════════════════════════════════════════════
    // 3. SMB DATA SYNC (Coexistence only)
    // ════════════════════════════════════════════════════════════

    /**
     * Initiate SMB contact + message history sync for coexistence onboarding.
     *
     * Required when a customer migrates their existing WhatsApp number
     * to Cloud API via the APP_ONBOARDING flow. Without these calls:
     *   - Contacts won't be visible in the new platform
     *   - Previous chat history will be lost
     *
     * Equivalent of PHP:
     *   POST /{phoneId}/smb_app_data  sync_type=smb_app_state_sync
     *   POST /{phoneId}/smb_app_data  sync_type=history
     *
     * Both calls are best-effort — failure doesn't block onboarding.
     * Meta processes these asynchronously; data appears over minutes/hours.
     *
     * @param phoneNumberId  The registered phone number ID
     * @param accessToken    Valid access token
     */
    public void initiateSmbSync(String phoneNumberId, String accessToken) {
        log.info("Initiating SMB sync for coexistence: phoneNumberId={}", phoneNumberId);

        // Step 1: Contact sync
        try {
            MetaApiResponse contactSync = metaRetry.executeWithContext(
                    "smbAppStateSync", phoneNumberId,
                    () -> metaApiClient.syncSmbAppState(phoneNumberId, accessToken));

            if (contactSync.isOk()) {
                log.info("SMB contact sync initiated: phoneNumberId={}", phoneNumberId);
            } else {
                log.warn("SMB contact sync returned error (non-fatal): phoneNumberId={}, error={}",
                        phoneNumberId, contactSync.getErrorMessage());
            }
        } catch (Exception ex) {
            log.warn("SMB contact sync failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
        }

        // Step 2: History sync
        try {
            MetaApiResponse historySync = metaRetry.executeWithContext(
                    "smbHistorySync", phoneNumberId,
                    () -> metaApiClient.syncSmbHistory(phoneNumberId, accessToken));

            if (historySync.isOk()) {
                log.info("SMB history sync initiated: phoneNumberId={}", phoneNumberId);
            } else {
                log.warn("SMB history sync returned error (non-fatal): phoneNumberId={}, error={}",
                        phoneNumberId, historySync.getErrorMessage());
            }
        } catch (Exception ex) {
            log.warn("SMB history sync failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
        }
    }
}