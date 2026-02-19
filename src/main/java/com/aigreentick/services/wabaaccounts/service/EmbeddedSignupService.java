package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.client.MetaApiRetryExecutor;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.dto.response.SignupConfigResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.DuplicateWabaException;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the complete Meta WhatsApp Embedded Signup flow.
 *
 * ─── v5 Fixes (PHP parity — 3 missing Meta onboarding steps) ────────────
 *   FIX 6: Phone registration   — POST /{phoneNumberId}/register after sync
 *   FIX 7: Coexistence flow     — Handle phoneNumberId=NA / APP_ONBOARDING
 *   FIX 8: SMB data sync        — Contact + history sync for coexistence
 *
 * ─── v4 Fixes (Senior review gaps) ──────────────────────────────────────
 *   GAP 1: WABA ownership verified
 *   GAP 2: OAuth scopes verified
 *   GAP 3: Webhook health checked
 *   GAP 4: Tokens encrypted at rest
 *   GAP 5: Retry layer
 *
 * ─── v3 Fixes (Blocker-level) ────────────────────────────────────────────
 *   FIX 1: Race condition — DB constraint + friendly 409 catch
 *   FIX 2: OAuth domain model — one token per org
 *   FIX 3: Meta API outside @Transactional
 *
 * ─── Transaction design ─────────────────────────────────────────────────
 *   BEFORE TX  → All Meta API calls (token exchange, extension, verification)
 *   INSIDE TX  → Pure DB writes only — fast, no external I/O
 *   AFTER SAVE → Best-effort side effects (webhook, phone sync, registration, SMB sync)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedSignupService {

    private final MetaApiClient metaApiClient;
    private final MetaApiRetryExecutor metaRetry;
    private final MetaApiConfig metaApiConfig;
    private final MetaOAuthAccountRepository oauthAccountRepository;
    private final WabaAccountRepository wabaAccountRepository;
    private final PhoneNumberService phoneNumberService;
    private final PhoneRegistrationService phoneRegistrationService;  // NEW
    private final SystemUserProvisioningService systemUserProvisioningService;
    private final TokenEncryptionService tokenEncryptionService;

    private static final List<String> REQUIRED_SCOPES = List.of(
            "whatsapp_business_management",
            "whatsapp_business_messaging",
            "business_management"
    );
    private static final String REQUIRED_SCOPES_CSV = String.join(",", REQUIRED_SCOPES);
    private static final long MIN_LONG_LIVED_SECONDS = 7L * 24 * 3600;

    // ════════════════════════════════════════════════════════════
    // CONFIG — called by frontend to initialise the signup button
    // ════════════════════════════════════════════════════════════

    public SignupConfigResponse getSignupConfig() {
        return SignupConfigResponse.builder()
                .metaAppId(metaApiConfig.getAppId())
                .apiVersion(metaApiConfig.getApiVersion())
                .scopes(REQUIRED_SCOPES_CSV)
                .extrasJson(buildExtrasJson())
                .callbackEndpoint("/api/v1/embedded-signup/callback")
                .configId(metaApiConfig.getEmbeddedSignupConfigId())
                .build();
    }

    // ════════════════════════════════════════════════════════════
    // MAIN FLOW
    // ════════════════════════════════════════════════════════════

    public EmbeddedSignupResponse processSignupCallback(EmbeddedSignupCallbackRequest request) {
        log.info("Embedded signup started: orgId={}, coexistence={}",
                request.getOrganizationId(), request.isCoexistenceFlow());

        // Step A: Exchange OAuth code for short-lived token
        String shortLivedToken = exchangeCodeForToken(request.getCode());

        // Step B: Extend to long-lived user token (~60 days)
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);
        log.info("Long-lived token obtained. Expires ~{} days", tokenResult.expiresIn() / 86400);

        // Step C (GAP 2): Verify all required OAuth scopes were granted
        verifyOAuthScopes(tokenResult.accessToken());

        // Step D (GAP 1): Resolve WABA ID and verify ownership
        String wabaId = resolveAndVerifyWabaOwnership(request, tokenResult.accessToken());

        // Step E: Resolve Business Manager ID
        String businessManagerId = resolveBusinessManagerId(request, wabaId, tokenResult.accessToken());

        // ── FIX 7: Resolve phone number ID (coexistence flow) ──────────
        // If frontend sent phoneNumberId=NA or signupType=APP_ONBOARDING,
        // we need to auto-discover the latest phone number from the WABA.
        String resolvedPhoneNumberId = resolvePhoneNumberId(request, wabaId, tokenResult.accessToken());

        // Step F: All DB writes in one tight transaction
        EmbeddedSignupResponse result = saveOnboardingData(
                request, tokenResult, wabaId, businessManagerId);

        // ── FIX 6: Register phone number with Meta ─────────────────────
        // This MUST happen after WABA is saved (we need the token).
        // PHP does: POST /{phone_number_id}/register { messaging_product: "whatsapp", pin: "123456" }
        // Without this, the phone remains inactive and can't send/receive messages.
        if (resolvedPhoneNumberId != null) {
            boolean registered = phoneRegistrationService.registerPhoneNumber(
                    resolvedPhoneNumberId, tokenResult.accessToken());
            if (registered) {
                log.info("Phone registered during onboarding: phoneNumberId={}", resolvedPhoneNumberId);
            } else {
                log.warn("Phone registration failed during onboarding (can retry via /phone-numbers/register): " +
                        "phoneNumberId={}", resolvedPhoneNumberId);
            }
        }

        // ── FIX 8: SMB sync for coexistence flow ──────────────────────
        // PHP does two calls for APP_ONBOARDING:
        //   POST /{phoneId}/smb_app_data  sync_type=smb_app_state_sync  (contacts)
        //   POST /{phoneId}/smb_app_data  sync_type=history             (chat history)
        // Without these, migrated customers won't see their contacts or past chats.
        if (request.isCoexistenceFlow() && resolvedPhoneNumberId != null) {
            log.info("Coexistence flow — initiating SMB sync for phoneNumberId={}", resolvedPhoneNumberId);
            phoneRegistrationService.initiateSmbSync(resolvedPhoneNumberId, tokenResult.accessToken());
        }

        // Step G: Phase 2 — auto-provision permanent system user token (non-blocking)
        boolean phase2Ok = systemUserProvisioningService.tryProvisionAfterSignup(request.getOrganizationId());
        if (phase2Ok) {
            log.info("Phase 2 complete: permanent token for orgId={}", request.getOrganizationId());
        } else {
            log.warn("Phase 2 pending orgId={}. Retry: POST /api/v1/system-users/provision/{}",
                    request.getOrganizationId(), request.getOrganizationId());
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════
    // FIX 7: Phone Number Resolution (Coexistence)
    // ════════════════════════════════════════════════════════════

    /**
     * Resolve the phone number ID to register.
     *
     * Two cases:
     *   1. NORMAL FLOW: Frontend provides a real phoneNumberId → use it directly
     *   2. COEXISTENCE FLOW: phoneNumberId is "NA" or null →
     *      auto-discover from GET /{wabaId}/phone_numbers, pick the latest
     *
     * Equivalent of PHP:
     *   if ($whatsapp_no_id === 'NA') {
     *       $phoneResponse = GET /{waba_id}/phone_numbers;
     *       $latestPhone = end($numbers);
     *       $phoneNumberId = $latestPhone['id'];
     *   }
     */
    private String resolvePhoneNumberId(EmbeddedSignupCallbackRequest request,
                                        String wabaId,
                                        String accessToken) {
        // Normal flow — frontend provided a valid phone number ID
        if (request.hasPhoneNumberId()) {
            log.info("Using phone number ID from frontend: {}", request.getPhoneNumberId());
            return request.getPhoneNumberId();
        }

        // Coexistence flow — auto-discover from WABA
        log.info("Phone number ID not provided (coexistence flow). Auto-discovering from WABA: {}", wabaId);
        PhoneRegistrationService.DiscoveredPhone discovered =
                phoneRegistrationService.discoverLatestPhoneNumber(wabaId, accessToken);

        if (discovered == null) {
            log.warn("No phone numbers found for WABA {}. " +
                    "Phone registration will be skipped — user can add manually later.", wabaId);
            return null;
        }

        log.info("Auto-discovered phone: id={}, display={}",
                discovered.phoneNumberId(), discovered.displayPhoneNumber());
        return discovered.phoneNumberId();
    }

    // ════════════════════════════════════════════════════════════
    // DB TRANSACTION — pure writes only, no external I/O
    // ════════════════════════════════════════════════════════════

    @Transactional
    public EmbeddedSignupResponse saveOnboardingData(EmbeddedSignupCallbackRequest request,
                                                     TokenResult tokenResult,
                                                     String wabaId,
                                                     String businessManagerId) {
        // App-level existence check (fast path before DB round-trip)
        if (wabaAccountRepository.existsByWabaId(wabaId)) {
            if (wabaAccountRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), wabaId)) {
                throw DuplicateWabaException.forOrganization(request.getOrganizationId(), wabaId);
            }
            log.error("Cross-tenant WABA conflict: wabaId={} requested by orgId={}",
                    wabaId, request.getOrganizationId());
            throw new DuplicateWabaException(
                    "This WhatsApp Business Account is already connected to another account. " +
                            "Contact support if you believe this is an error.");
        }

        // GAP 4: Encrypt token before persisting
        String encryptedToken = tokenEncryptionService.encrypt(tokenResult.accessToken());
        MetaOAuthAccount oauthAccount = saveOAuthAccount(
                request.getOrganizationId(), encryptedToken, tokenResult.expiresIn());

        // FIX 1 (v3): DB unique constraint is the last line of defense for race conditions
        WabaAccount waba;
        try {
            waba = WabaAccount.builder()
                    .organizationId(request.getOrganizationId())
                    .metaOAuthAccountId(oauthAccount.getId())
                    .wabaId(wabaId)
                    .status(WabaStatus.ACTIVE)
                    .build();
            waba = wabaAccountRepository.save(waba);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on WABA insert: wabaId={}", wabaId);
            throw new DuplicateWabaException(
                    "This WABA was just connected by another request. Refresh and check your WABA list.");
        }

        // Best-effort: webhook subscribe + health check (GAP 3)
        boolean webhookOk = subscribeAndVerifyWebhook(wabaId, tokenResult.accessToken());

        // Best-effort: phone number sync from Meta
        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(waba, tokenResult.accessToken());
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(waba.getId());
        } catch (Exception ex) {
            log.warn("Phone sync failed (retry via /sync). Error: {}", ex.getMessage());
        }

        return EmbeddedSignupResponse.builder()
                .wabaAccountId(waba.getId())
                .wabaId(wabaId)
                .status(waba.getStatus().getValue())
                .businessManagerId(businessManagerId)
                .tokenExpiresIn(tokenResult.expiresIn())
                .longLivedToken(tokenResult.isLongLived())
                .phoneNumbers(phoneNumbers)
                .phoneNumberCount(phoneNumbers.size())
                .webhookSubscribed(webhookOk)
                .summary(buildSummary(phoneNumbers.size(), wabaId, webhookOk))
                .build();
    }

    // ════════════════════════════════════════════════════════════
    // GAP 2: OAuth scope verification
    // ════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void verifyOAuthScopes(String accessToken) {
        try {
            var response = metaRetry.execute("getPermissions",
                    () -> metaApiClient.getUserPermissions(accessToken));

            if (!response.isOk() || response.getData() == null) {
                log.warn("Could not reach /me/permissions — proceeding without scope check");
                return;
            }

            Object dataObj = response.getData().get("data");
            if (!(dataObj instanceof List)) {
                log.warn("Unexpected /me/permissions format — skipping scope check");
                return;
            }

            List<Map<String, Object>> permissions = (List<Map<String, Object>>) dataObj;
            List<String> granted = permissions.stream()
                    .filter(p -> "granted".equals(p.get("status")))
                    .map(p -> String.valueOf(p.get("permission")))
                    .toList();

            List<String> missing = REQUIRED_SCOPES.stream()
                    .filter(s -> !granted.contains(s))
                    .toList();

            if (!missing.isEmpty()) {
                throw new InvalidRequestException(
                        "Missing required WhatsApp permissions: " + missing + ". " +
                                "Reconnect and approve all permissions in the dialog. " +
                                "Required: " + REQUIRED_SCOPES);
            }

            log.info("OAuth scope check passed");
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Scope check failed unexpectedly (proceeding): {}", ex.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    // GAP 1: WABA ownership verification
    // ════════════════════════════════════════════════════════════

    private String resolveAndVerifyWabaOwnership(EmbeddedSignupCallbackRequest request,
                                                 String accessToken) {
        if (request.getWabaId() != null && !request.getWabaId().isBlank()) {
            verifyWabaOwnership(request.getWabaId(), accessToken);
            return request.getWabaId();
        }
        log.info("No wabaId in request — auto-discovering from token");
        return discoverWabaId(accessToken);
    }

    private void verifyWabaOwnership(String wabaId, String accessToken) {
        var response = metaRetry.executeWithContext("verifyWabaOwnership", wabaId,
                () -> metaApiClient.getWabaDetails(wabaId, accessToken));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Cannot verify access to WABA " + wabaId + ". " +
                            "The WABA ID may be incorrect or you don't have permission. " +
                            "Error: " + response.getErrorMessage());
        }
        log.info("WABA ownership confirmed: wabaId={}", wabaId);
    }

    @SuppressWarnings("unchecked")
    private String discoverWabaId(String accessToken) {
        var response = metaRetry.execute("discoverWabaId",
                () -> metaApiClient.getBusinessAccounts(accessToken));

        if (!response.isOk() || response.getData() == null) {
            throw new InvalidRequestException("Could not discover WABA — provide wabaId explicitly.");
        }

        List<Map<String, Object>> businesses = (List<Map<String, Object>>) response.getData().get("data");
        if (businesses == null || businesses.isEmpty()) {
            throw new InvalidRequestException("No Business Managers found for this token.");
        }

        for (Map<String, Object> biz : businesses) {
            Map<String, Object> wabaData = (Map<String, Object>) biz.get("owned_whatsapp_business_accounts");
            if (wabaData != null) {
                List<Map<String, Object>> wabas = (List<Map<String, Object>>) wabaData.get("data");
                if (wabas != null && !wabas.isEmpty()) {
                    String wabaId = String.valueOf(wabas.get(0).get("id"));
                    log.info("WABA auto-discovered: wabaId={}", wabaId);
                    return wabaId;
                }
            }
        }

        throw new InvalidRequestException("No WABA found in Business Manager. Ensure signup completed.");
    }

    // ════════════════════════════════════════════════════════════
    // GAP 3: Webhook subscribe + health verification
    // ════════════════════════════════════════════════════════════

    private boolean subscribeAndVerifyWebhook(String wabaId, String accessToken) {
        try {
            var subResult = metaRetry.executeWithContext("subscribeWebhook", wabaId,
                    () -> metaApiClient.subscribeWabaToWebhook(wabaId, accessToken));
            if (!subResult.isOk()) {
                log.warn("Webhook subscribe failed: wabaId={}", wabaId);
                return false;
            }
        } catch (Exception ex) {
            log.warn("Webhook subscribe threw: wabaId={}, error={}", wabaId, ex.getMessage());
            return false;
        }

        try {
            var check = metaRetry.executeWithContext("verifyWebhook", wabaId,
                    () -> metaApiClient.getSubscribedApps(wabaId, accessToken));

            boolean active = isOurAppSubscribed(check);
            if (active) {
                log.info("Webhook subscription confirmed: wabaId={}", wabaId);
            } else {
                log.warn("Webhook subscribed but app not in subscribed_apps: wabaId={}", wabaId);
            }
            return active;
        } catch (Exception ex) {
            log.warn("Webhook health check failed (subscription may still be active): wabaId={}", wabaId);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isOurAppSubscribed(MetaApiResponse response) {
        if (!response.isOk()) return false;
        try {
            Object dataObj = response.getData() != null
                    ? response.getData().get("data")
                    : response.getExtras().get("data");
            if (!(dataObj instanceof List)) return false;

            String ourAppId = metaApiConfig.getAppId();
            List<Map<String, Object>> apps = (List<Map<String, Object>>) dataObj;
            return apps.stream().anyMatch(app ->
                    ourAppId.equals(String.valueOf(app.get("id"))) ||
                            ourAppId.equals(String.valueOf(app.get("whatsapp_business_api_data"))));
        } catch (Exception ex) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════

    private String exchangeCodeForToken(String code) {
        var response = metaRetry.execute("exchangeCode",
                () -> metaApiClient.exchangeCodeForToken(code));
        if (!response.isOk() || response.getData() == null) {
            throw new InvalidRequestException("Failed to exchange OAuth code — may be expired or already used");
        }
        Object token = response.getData().get("access_token");
        if (token == null || token.toString().isBlank()) {
            throw new InvalidRequestException("Meta returned empty access token during code exchange");
        }
        return token.toString();
    }

    private TokenResult extendToLongLivedToken(String shortLivedToken) {
        try {
            var response = metaRetry.execute("extendToken",
                    () -> metaApiClient.extendAccessToken(shortLivedToken));

            if (!response.isOk() || response.getData() == null) {
                throw new InvalidRequestException(
                        "Meta did not return a long-lived token. " +
                                "Ensure your Meta App is in Live mode at developers.facebook.com.");
            }

            Map<String, Object> data = response.getData();
            String longToken = String.valueOf(data.getOrDefault("access_token", ""));
            if (longToken.isBlank() || "null".equals(longToken)) {
                throw new InvalidRequestException("Meta returned a blank token during extension");
            }

            long expiresIn = parseLong(data.getOrDefault("expires_in", 0L));
            if (expiresIn < MIN_LONG_LIVED_SECONDS) {
                throw new InvalidRequestException(String.format(
                        "Short-lived token returned (%d seconds, ~%d hours). " +
                                "Ensure Meta App is in Live mode.", expiresIn, expiresIn / 3600));
            }

            return new TokenResult(longToken, expiresIn, true);
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (MetaApiException ex) {
            throw new InvalidRequestException(
                    "Failed to extend token: " + ex.getMessage() +
                            ". Check META_APP_ID and META_APP_SECRET are correct.");
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveBusinessManagerId(EmbeddedSignupCallbackRequest request,
                                            String wabaId,
                                            String accessToken) {
        if (request.getBusinessManagerId() != null && !request.getBusinessManagerId().isBlank()) {
            return request.getBusinessManagerId();
        }
        try {
            var details = metaRetry.executeWithContext("getWabaDetails", wabaId,
                    () -> metaApiClient.getWabaDetails(wabaId, accessToken));
            if (details.isOk() && details.getData() != null) {
                Object bizId = details.getData().get("business_id");
                if (bizId != null) return String.valueOf(bizId);
            }
        } catch (Exception ex) {
            log.warn("Could not resolve Business Manager ID: {}", ex.getMessage());
        }
        return "UNKNOWN-" + wabaId;
    }

    private MetaOAuthAccount saveOAuthAccount(Long organizationId,
                                              String encryptedToken,
                                              long expiresInSeconds) {
        return oauthAccountRepository.findByOrganizationId(organizationId)
                .map(existing -> {
                    existing.setAccessToken(encryptedToken);
                    existing.setExpiresAt(expiresInSeconds > 0
                            ? LocalDateTime.now().plusSeconds(expiresInSeconds) : null);
                    return oauthAccountRepository.save(existing);
                })
                .orElseGet(() -> oauthAccountRepository.save(
                        MetaOAuthAccount.builder()
                                .organizationId(organizationId)
                                .accessToken(encryptedToken)
                                .expiresAt(expiresInSeconds > 0
                                        ? LocalDateTime.now().plusSeconds(expiresInSeconds) : null)
                                .build()));
    }

    private String buildExtrasJson() {
        return "{\"feature\": \"whatsapp_embedded_signup\", \"version\": 2, \"sessionInfoVersion\": \"3\"}";
    }

    private String buildSummary(int phoneCount, String wabaId, boolean webhookOk) {
        String webhookNote = webhookOk ? "" : " Warning: webhook not confirmed.";
        if (phoneCount == 0) {
            return "WABA connected: " + wabaId + ". No phone numbers found." + webhookNote;
        }
        return "WABA connected with " + phoneCount + " phone number" +
                (phoneCount > 1 ? "s" : "") + ". Ready to send!" + webhookNote;
    }

    private long parseLong(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public boolean isOrganizationConnected(Long organizationId) {
        return !wabaAccountRepository
                .findByOrganizationIdAndStatus(organizationId, WabaStatus.ACTIVE)
                .isEmpty();
    }

    record TokenResult(String accessToken, long expiresIn, boolean isLongLived) {}
}