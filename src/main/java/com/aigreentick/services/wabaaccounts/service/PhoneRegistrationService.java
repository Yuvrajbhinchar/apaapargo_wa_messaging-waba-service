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

@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneRegistrationService {

    private final MetaApiClient metaApiClient;
    private final MetaApiRetryExecutor metaRetry;

    @Value("${meta.default-registration-pin:123456}")
    private String defaultRegistrationPin;

    // ════════════════════════════════════════════════════════════
    // 1. REGISTER PHONE NUMBER
    // Response: { "success": true } — isOk() handles this correctly
    // ════════════════════════════════════════════════════════════

    public boolean registerPhoneNumber(String phoneNumberId, String accessToken) {
        return registerPhoneNumber(phoneNumberId, accessToken, defaultRegistrationPin);
    }

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

            String errorMsg = response.getErrorMessage();
            // Already registered is not a failure — phone is usable
            if (errorMsg != null && errorMsg.contains("already registered")) {
                log.info("Phone already registered (OK): phoneNumberId={}", phoneNumberId);
                return true;
            }

            log.warn("Phone registration returned error: phoneNumberId={}, error={}", phoneNumberId, errorMsg);
            return false;

        } catch (MetaApiException ex) {
            log.warn("Phone registration failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    // 2. DISCOVER PHONE NUMBER (Coexistence flow)
    // FIX: GET /{wabaId}/phone_numbers returns { "data": [...] }
    //      Use getDataAsList() directly — not getData().get("data")
    // ════════════════════════════════════════════════════════════

    public DiscoveredPhone discoverLatestPhoneNumber(String wabaId, String accessToken) {
        try {
            log.info("Discovering phone numbers for WABA: wabaId={}", wabaId);

            MetaApiResponse response = metaRetry.executeWithContext(
                    "discoverPhones", wabaId,
                    () -> metaApiClient.getPhoneNumbers(wabaId, accessToken));

            if (!response.isOk()) {
                log.warn("Failed to fetch phone numbers for WABA: wabaId={}, error={}",
                        wabaId, response.getErrorMessage());
                return null;
            }

            // FIX: phone_numbers returns { "data": [ {...} ] }
            // data is a JSON array → getDataAsList() returns it directly
            // OLD BROKEN CODE:
            //   Object dataObj = response.getData().get("data");  // getData() = null → NPE
            List<Map<String, Object>> numbers = response.getDataAsList();

            if (numbers == null || numbers.isEmpty()) {
                log.warn("No phone numbers found for WABA: wabaId={}", wabaId);
                return null;
            }

            // Pick the LATEST added phone (last in array, same as PHP: end($numbers))
            Map<String, Object> latestPhone = numbers.get(numbers.size() - 1);
            String phoneId = String.valueOf(latestPhone.get("id"));
            String displayNumber = latestPhone.get("display_phone_number") != null
                    ? String.valueOf(latestPhone.get("display_phone_number"))
                    : null;

            log.info("Discovered latest phone: phoneNumberId={}, display={}", phoneId, displayNumber);
            return new DiscoveredPhone(phoneId, displayNumber);

        } catch (Exception ex) {
            log.error("Phone discovery failed for WABA {}: {}", wabaId, ex.getMessage());
            return null;
        }
    }

    public record DiscoveredPhone(String phoneNumberId, String displayPhoneNumber) {
        public String cleanedPhoneNumber() {
            if (displayPhoneNumber == null) return null;
            return displayPhoneNumber.replaceAll("[\\s+]+", "");
        }
    }

    // ════════════════════════════════════════════════════════════
    // 3. SMB DATA SYNC (Coexistence only)
    // Response: { "success": true } — isOk() handles this correctly
    // ════════════════════════════════════════════════════════════

    public void initiateSmbSync(String phoneNumberId, String accessToken) {
        log.info("Initiating SMB sync for coexistence: phoneNumberId={}", phoneNumberId);

        // Contact sync
        try {
            MetaApiResponse contactSync = metaRetry.executeWithContext(
                    "smbAppStateSync", phoneNumberId,
                    () -> metaApiClient.syncSmbAppState(phoneNumberId, accessToken));

            if (contactSync.isOk()) {
                log.info("SMB contact sync initiated: phoneNumberId={}", phoneNumberId);
            } else {
                log.warn("SMB contact sync error (non-fatal): phoneNumberId={}, error={}",
                        phoneNumberId, contactSync.getErrorMessage());
            }
        } catch (Exception ex) {
            log.warn("SMB contact sync failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
        }

        // History sync
        try {
            MetaApiResponse historySync = metaRetry.executeWithContext(
                    "smbHistorySync", phoneNumberId,
                    () -> metaApiClient.syncSmbHistory(phoneNumberId, accessToken));

            if (historySync.isOk()) {
                log.info("SMB history sync initiated: phoneNumberId={}", phoneNumberId);
            } else {
                log.warn("SMB history sync error (non-fatal): phoneNumberId={}, error={}",
                        phoneNumberId, historySync.getErrorMessage());
            }
        } catch (Exception ex) {
            log.warn("SMB history sync failed (non-fatal): phoneNumberId={}, error={}",
                    phoneNumberId, ex.getMessage());
        }
    }
}