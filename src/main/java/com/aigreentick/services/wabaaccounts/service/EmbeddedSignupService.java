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
 * ═══════════════════════════════════════════════════════════════════
 * TRANSACTION BOUNDARY FIX
 * ═══════════════════════════════════════════════════════════════════
 *
 * PROBLEM:
 * saveOnboardingData() was @Transactional but called external HTTP:
 *   - subscribeAndVerifyWebhook()              → 10-30s HTTP to Meta
 *   - phoneNumberService.syncPhoneNumbersFromMeta() → 5-15s HTTP to Meta
 *
 * This held a DB connection open for the entire duration of both calls.
 * With Hikari max-pool-size=10 and 10 concurrent signups, all connections
 * are occupied waiting on Meta, and the next signup blocks on Hikari's
 * connection-timeout (30s), then fails with a timeout exception.
 *
 * FIX:
 * Renamed the transactional method to persistOnboardingData() — pure DB writes only.
 * Moved webhook subscribe + phone sync to processSignupCallback() which is NOT
 * transactional. DB connection is released after commit, then external calls happen.
 *
 * NEW FLOW:
 *   BEFORE TX  → Steps A-F: All Meta API calls (no DB connection held)
 *   INSIDE TX  → Step G: persistOnboardingData() — pure DB writes (< 50ms)
 *   AFTER TX   → Step G2: webhookSubscribe (best-effort, HTTP)
 *              → Step G3: phoneSyncFromMeta (best-effort, HTTP)
 *              → Step H: phoneRegistration (best-effort, HTTP)
 *              → Step I: smbSync (best-effort, HTTP)
 *              → Step J: phase2 systemUser (best-effort, HTTP)
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
    private final PhoneRegistrationService phoneRegistrationService;
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
    // MAIN FLOW — NOT @Transactional (external calls happen here)
    // ════════════════════════════════════════════════════════════

    public EmbeddedSignupResponse processSignupCallback(EmbeddedSignupCallbackRequest request) {
        log.info("Embedded signup started: orgId={}, coexistence={}",
                request.getOrganizationId(), request.isCoexistenceFlow());

        // ── Step A: Exchange OAuth code for short-lived token ──
        String shortLivedToken = exchangeCodeForToken(request.getCode());

        // ── Step B: Extend to long-lived user token (~60 days) ──
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);
        log.info("Long-lived token obtained. Expires ~{} days", tokenResult.expiresIn() / 86400);

        // ── Step C: Verify all required OAuth scopes ──
        verifyOAuthScopes(tokenResult.accessToken());

        // ── Step D: Resolve WABA ID and verify ownership ──
        String wabaId = resolveAndVerifyWabaOwnership(request, tokenResult.accessToken());

        // ── Step E: Resolve Business Manager ID ──
        String businessManagerId = resolveBusinessManagerId(request, wabaId, tokenResult.accessToken());

        // ── Step F: Resolve phone number ID (coexistence auto-discovers) ──
        String resolvedPhoneNumberId = resolvePhoneNumberId(request, wabaId, tokenResult.accessToken());

        // ══════════════════════════════════════════════════════════
        // Step G: DB writes — tight transaction, no external I/O
        // persistOnboardingData() is @Transactional. DB connection
        // is acquired, writes execute (< 50ms), connection released.
        // ══════════════════════════════════════════════════════════
        PersistedOnboardingData saved = persistOnboardingData(
                request, tokenResult, wabaId, businessManagerId);

        // ══════════════════════════════════════════════════════════
        // Steps G2-J: Best-effort side effects — OUTSIDE transaction
        // DB connection is already released. These are all HTTP calls
        // to Meta that can take 5-30s each. No DB connection is held.
        // ══════════════════════════════════════════════════════════

        // ── Step G2: Webhook subscribe (best-effort) ──
        boolean webhookOk = subscribeAndVerifyWebhook(wabaId, tokenResult.accessToken());

        // ── Step G3: Phone number sync from Meta (best-effort) ──
        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(saved.waba(), tokenResult.accessToken());
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(saved.waba().getId());
        } catch (Exception ex) {
            log.warn("Phone sync failed (retry via /{}/sync). Error: {}",
                    saved.waba().getId(), ex.getMessage());
        }

        // ── Step H: Register phone number with Meta (best-effort) ──
        if (resolvedPhoneNumberId != null) {
            boolean registered = phoneRegistrationService.registerPhoneNumber(
                    resolvedPhoneNumberId, tokenResult.accessToken());
            if (registered) {
                log.info("Phone registered during onboarding: phoneNumberId={}", resolvedPhoneNumberId);
            } else {
                log.warn("Phone registration failed (retry via /phone-numbers/register): phoneNumberId={}",
                        resolvedPhoneNumberId);
            }
        }

        // ── Step I: SMB sync for coexistence flow (best-effort) ──
        if (request.isCoexistenceFlow() && resolvedPhoneNumberId != null) {
            log.info("Coexistence flow — initiating SMB sync for phoneNumberId={}", resolvedPhoneNumberId);
            phoneRegistrationService.initiateSmbSync(resolvedPhoneNumberId, tokenResult.accessToken());
        }

        // ── Step J: Phase 2 — system user provisioning (best-effort) ──
        boolean phase2Ok = systemUserProvisioningService.tryProvisionAfterSignup(request.getOrganizationId());
        if (phase2Ok) {
            log.info("Phase 2 complete: permanent token for orgId={}", request.getOrganizationId());
        } else {
            log.warn("Phase 2 pending orgId={}. Retry: POST /api/v1/system-users/provision/{}",
                    request.getOrganizationId(), request.getOrganizationId());
        }

        // ── Build response ──
        return EmbeddedSignupResponse.builder()
                .wabaAccountId(saved.waba().getId())
                .wabaId(wabaId)
                .status(saved.waba().getStatus().getValue())
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
    // STEP A: Token Exchange
    // ════════════════════════════════════════════════════════════

    private String exchangeCodeForToken(String code) {
        var response = metaRetry.execute("exchangeCode",
                () -> metaApiClient.exchangeCodeForToken(code));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Failed to exchange OAuth code: " + response.getErrorMessage() +
                            ". Code may be expired or already used.");
        }

        Object token = response.getFlatValue("access_token");
        if (token == null || token.toString().isBlank()) {
            log.error("Token exchange response extras: {}", response.getExtras());
            throw new InvalidRequestException(
                    "Meta returned empty access token during code exchange. " +
                            "Ensure META_APP_ID and META_APP_SECRET are correct.");
        }

        return token.toString();
    }

    // ════════════════════════════════════════════════════════════
    // STEP B: Token Extension
    // ════════════════════════════════════════════════════════════

    private TokenResult extendToLongLivedToken(String shortLivedToken) {
        try {
            var response = metaRetry.execute("extendToken",
                    () -> metaApiClient.extendAccessToken(shortLivedToken));

            if (!response.isOk()) {
                throw new InvalidRequestException(
                        "Meta rejected token extension: " + response.getErrorMessage() + ". " +
                                "Ensure your Meta App is in Live mode at developers.facebook.com.");
            }

            Object tokenObj = response.getFlatValue("access_token");
            Object expiresObj = response.getFlatValue("expires_in");

            if (tokenObj == null || tokenObj.toString().isBlank() || "null".equals(tokenObj.toString())) {
                log.error("Token extension response extras: {}", response.getExtras());
                throw new InvalidRequestException(
                        "Meta returned blank token during extension. " +
                                "Ensure Meta App is in Live mode.");
            }

            String longToken = tokenObj.toString();
            long expiresIn = parseLong(expiresObj != null ? expiresObj : 0L);

            if (expiresIn < MIN_LONG_LIVED_SECONDS) {
                throw new InvalidRequestException(String.format(
                        "Received short-lived token (%d seconds ≈ %d hours). " +
                                "Ensure Meta App is in Live mode — sandbox mode only returns short-lived tokens.",
                        expiresIn, expiresIn / 3600));
            }

            return new TokenResult(longToken, expiresIn, true);

        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (MetaApiException ex) {
            throw new InvalidRequestException(
                    "Failed to extend token: " + ex.getMessage() +
                            ". Verify META_APP_ID and META_APP_SECRET are correct.");
        }
    }

    // ════════════════════════════════════════════════════════════
    // STEP C: OAuth Scope Verification
    // ════════════════════════════════════════════════════════════

    private void verifyOAuthScopes(String accessToken) {
        try {
            var response = metaRetry.execute("getPermissions",
                    () -> metaApiClient.getUserPermissions(accessToken));

            if (!response.isOk()) {
                log.warn("Could not verify OAuth scopes: {} — proceeding without scope check",
                        response.getErrorMessage());
                return;
            }

            List<Map<String, Object>> permissions = response.getDataAsList();

            if (permissions == null) {
                log.warn("Unexpected /me/permissions format (expected array in data) — skipping scope check");
                return;
            }

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
                                "Reconnect and approve all permissions in the signup dialog. " +
                                "Required: " + REQUIRED_SCOPES);
            }

            log.info("OAuth scope check passed. Granted: {}", granted);

        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Scope check failed unexpectedly (proceeding): {}", ex.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    // STEP D: WABA Ownership Verification
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
                            "The WABA ID may be incorrect or the token lacks permission. " +
                            "Error: " + response.getErrorMessage());
        }
        log.info("WABA ownership confirmed: wabaId={}", wabaId);
    }

    @SuppressWarnings("unchecked")
    private String discoverWabaId(String accessToken) {
        var response = metaRetry.execute("discoverWabaId",
                () -> metaApiClient.getBusinessAccounts(accessToken));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Could not fetch business accounts: " + response.getErrorMessage() +
                            ". Provide wabaId explicitly.");
        }

        List<Map<String, Object>> businesses = response.getDataAsList();

        if (businesses == null || businesses.isEmpty()) {
            throw new InvalidRequestException(
                    "No Business Managers found for this token. " +
                            "Ensure the user has completed the embedded signup flow.");
        }

        for (Map<String, Object> biz : businesses) {
            Object wabaDataObj = biz.get("owned_whatsapp_business_accounts");
            if (wabaDataObj instanceof Map) {
                Map<String, Object> wabaData = (Map<String, Object>) wabaDataObj;
                Object wabas = wabaData.get("data");
                if (wabas instanceof List) {
                    List<Map<String, Object>> wabaList = (List<Map<String, Object>>) wabas;
                    if (!wabaList.isEmpty()) {
                        String wabaId = String.valueOf(wabaList.get(0).get("id"));
                        log.info("WABA auto-discovered: wabaId={}", wabaId);
                        return wabaId;
                    }
                }
            }
        }

        throw new InvalidRequestException(
                "No WABA found in any Business Manager. " +
                        "Ensure the embedded signup completed successfully.");
    }

    // ════════════════════════════════════════════════════════════
    // STEP E: Business Manager ID Resolution
    // ════════════════════════════════════════════════════════════

    private String resolveBusinessManagerId(EmbeddedSignupCallbackRequest request,
                                            String wabaId,
                                            String accessToken) {
        if (request.getBusinessManagerId() != null && !request.getBusinessManagerId().isBlank()) {
            return request.getBusinessManagerId();
        }
        try {
            var details = metaRetry.executeWithContext("getWabaDetails", wabaId,
                    () -> metaApiClient.getWabaDetails(wabaId, accessToken));

            if (details.isOk()) {
                Object bizId = details.getFlatValue("business_id");
                if (bizId != null && !String.valueOf(bizId).isBlank()) {
                    log.info("Business Manager ID resolved: {}", bizId);
                    return String.valueOf(bizId);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not resolve Business Manager ID: {}", ex.getMessage());
        }
        return "UNKNOWN-" + wabaId;
    }

    // ════════════════════════════════════════════════════════════
    // STEP F: Phone Number ID Resolution (Coexistence)
    // ════════════════════════════════════════════════════════════

    private String resolvePhoneNumberId(EmbeddedSignupCallbackRequest request,
                                        String wabaId,
                                        String accessToken) {
        if (request.hasPhoneNumberId()) {
            log.info("Using phone number ID from frontend: {}", request.getPhoneNumberId());
            return request.getPhoneNumberId();
        }

        log.info("Phone number ID not provided (coexistence). Auto-discovering from WABA: {}", wabaId);
        PhoneRegistrationService.DiscoveredPhone discovered =
                phoneRegistrationService.discoverLatestPhoneNumber(wabaId, accessToken);

        if (discovered == null) {
            log.warn("No phone numbers found for WABA {}. Registration skipped.", wabaId);
            return null;
        }

        log.info("Auto-discovered phone: id={}, display={}",
                discovered.phoneNumberId(), discovered.displayPhoneNumber());
        return discovered.phoneNumberId();
    }

    // ════════════════════════════════════════════════════════════
    // STEP G: DB TRANSACTION — pure writes only, no external I/O
    // ════════════════════════════════════════════════════════════
    //
    // ═══ TRANSACTION BOUNDARY FIX ═══
    //
    // BEFORE (BROKEN):
    //   @Transactional saveOnboardingData() did:
    //     1. DB duplicate check           ← OK in TX
    //     2. DB save OAuth account         ← OK in TX
    //     3. DB save WABA                  ← OK in TX
    //     4. subscribeAndVerifyWebhook()   ← 10-30s HTTP! Holds DB connection!
    //     5. syncPhoneNumbersFromMeta()    ← 5-15s HTTP! Holds DB connection!
    //
    //   With Hikari max-pool-size=10, just 10 concurrent signups exhaust
    //   the pool. Signup #11 blocks 30s on getConnection() then fails.
    //
    // AFTER (FIXED):
    //   @Transactional persistOnboardingData() does ONLY:
    //     1. DB duplicate check
    //     2. DB save OAuth account
    //     3. DB save WABA
    //   → Commits, releases DB connection (< 50ms total)
    //
    //   processSignupCallback() (NOT transactional) then does:
    //     4. subscribeAndVerifyWebhook()   ← no DB connection held
    //     5. syncPhoneNumbersFromMeta()    ← no DB connection held
    //
    // ════════════════════════════════════════════════════════════

    /**
     * Persist onboarding data in a tight transaction — PURE DB WRITES ONLY.
     *
     * No external HTTP calls. No Meta API calls. Just duplicate checks + inserts.
     * Returns the persisted entities so the caller can use them for external calls
     * AFTER the transaction commits and the DB connection is released.
     */
    @Transactional
    public PersistedOnboardingData persistOnboardingData(
            EmbeddedSignupCallbackRequest request,
            TokenResult tokenResult,
            String wabaId,
            String businessManagerId) {

        // Global uniqueness check — is this WABA already connected to ANY org?
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

        String encryptedToken = tokenEncryptionService.encrypt(tokenResult.accessToken());
        MetaOAuthAccount oauthAccount = saveOAuthAccount(
                request.getOrganizationId(), encryptedToken, tokenResult.expiresIn());

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

        log.info("Onboarding data persisted: wabaAccountId={}, orgId={}",
                waba.getId(), request.getOrganizationId());

        return new PersistedOnboardingData(waba, oauthAccount);
    }

    /**
     * Immutable record holding the entities created during the transactional persist step.
     * Passed to the non-transactional caller for use in external API calls.
     */
    record PersistedOnboardingData(WabaAccount waba, MetaOAuthAccount oauthAccount) {}

    // ════════════════════════════════════════════════════════════
    // WEBHOOK: Subscribe + Health Check (called OUTSIDE transaction)
    // ════════════════════════════════════════════════════════════

    private boolean subscribeAndVerifyWebhook(String wabaId, String accessToken) {
        try {
            var subResult = metaRetry.executeWithContext("subscribeWebhook", wabaId,
                    () -> metaApiClient.subscribeWabaToWebhook(wabaId, accessToken));
            if (!subResult.isOk()) {
                log.warn("Webhook subscribe failed: wabaId={}, error={}", wabaId, subResult.getErrorMessage());
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
                log.warn("Webhook subscribed but app not confirmed in subscribed_apps: wabaId={}", wabaId);
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
            List<Map<String, Object>> apps = response.getDataAsList();
            if (apps == null) return false;

            String ourAppId = metaApiConfig.getAppId();
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
        if (value == null) return 0L;
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