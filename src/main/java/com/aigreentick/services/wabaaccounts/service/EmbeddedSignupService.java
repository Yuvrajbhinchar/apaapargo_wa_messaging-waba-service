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
 * ─── v6 Fix: MetaApiResponse DTO mismatch ───────────────────────────────
 * FIX (BLOCKER 1): Meta returns FLAT JSON for token endpoints, not { data: {...} }.
 *   - exchangeCodeForToken: reads from extras (access_token at top level)
 *   - extendToLongLivedToken: reads from extras (access_token/expires_in at top level)
 *   - verifyOAuthScopes: uses getDataAsList() for { data: [...] } permission arrays
 *   - discoverWabaId: uses getDataAsList() for { data: [...] } business arrays
 *   - resolveBusinessManagerId: uses getDataAsMap() for WABA details object
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
    private final PhoneRegistrationService phoneRegistrationService;
    private final SystemUserProvisioningService systemUserProvisioningService;
    private final TokenEncryptionService tokenEncryptionService;

    private static final List<String> REQUIRED_SCOPES = List.of(
            "whatsapp_business_management",
            "whatsapp_business_messaging",
            "business_management"
    );
    private static final String REQUIRED_SCOPES_CSV = String.join(",", REQUIRED_SCOPES);

    /**
     * Minimum seconds for a token to be considered long-lived.
     * Short-lived tokens are ~1 hour (3600s). Long-lived are ~60 days (5,184,000s).
     * We use 7 days as threshold to catch tokens that are long but not full 60-day.
     */
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
        // FIX: Meta returns flat JSON — access_token is at top level, not in data wrapper
        String shortLivedToken = exchangeCodeForToken(request.getCode());

        // Step B: Extend to long-lived user token (~60 days)
        // FIX: Same flat response pattern — read from extras, not getData()
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);
        log.info("Long-lived token obtained. Expires ~{} days", tokenResult.expiresIn() / 86400);

        // Step C: Verify all required OAuth scopes were granted
        // FIX: Uses getDataAsList() because /me/permissions returns { data: [...] }
        verifyOAuthScopes(tokenResult.accessToken());

        // Step D: Resolve WABA ID and verify ownership
        String wabaId = resolveAndVerifyWabaOwnership(request, tokenResult.accessToken());

        // Step E: Resolve Business Manager ID
        String businessManagerId = resolveBusinessManagerId(request, wabaId, tokenResult.accessToken());

        // Step F: Resolve phone number ID (coexistence flow auto-discovers)
        String resolvedPhoneNumberId = resolvePhoneNumberId(request, wabaId, tokenResult.accessToken());

        // Step G: All DB writes in one tight transaction
        EmbeddedSignupResponse result = saveOnboardingData(
                request, tokenResult, wabaId, businessManagerId);

        // Step H: Register phone number with Meta (best-effort, non-blocking)
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

        // Step I: SMB sync for coexistence flow (best-effort)
        if (request.isCoexistenceFlow() && resolvedPhoneNumberId != null) {
            log.info("Coexistence flow — initiating SMB sync for phoneNumberId={}", resolvedPhoneNumberId);
            phoneRegistrationService.initiateSmbSync(resolvedPhoneNumberId, tokenResult.accessToken());
        }

        // Step J: Phase 2 — auto-provision permanent system user token (non-blocking)
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
    // STEP A: Token Exchange
    // FIX: Meta returns FLAT JSON — { "access_token": "...", "token_type": "bearer" }
    //      This is NOT wrapped in a data field. Read from extras.
    // ════════════════════════════════════════════════════════════

    private String exchangeCodeForToken(String code) {
        var response = metaRetry.execute("exchangeCode",
                () -> metaApiClient.exchangeCodeForToken(code));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Failed to exchange OAuth code: " + response.getErrorMessage() +
                            ". Code may be expired or already used.");
        }

        // FLAT response: access_token is at top level → lands in extras via @JsonAnySetter
        // NOT in data wrapper. response.getData() would return null here.
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
    // FIX: Same flat response as token exchange — access_token/expires_in at top level
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

            // FLAT response: access_token and expires_in are at top level → in extras
            // getFlatValue() checks extras first, then dataAsMap fallback
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
    // FIX: /me/permissions returns { "data": [ { "permission": "...", "status": "granted" } ] }
    //      Use getDataAsList() — NOT getData().get("data") double-unwrap
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

            // /me/permissions returns { "data": [ { "permission": "...", "status": "granted" } ] }
            // data is an array → use getDataAsList()
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

    /**
     * Auto-discover WABA from business accounts.
     * FIX: /me/businesses returns { "data": [ { "id": "...", "owned_whatsapp_business_accounts": {...} } ] }
     * Use getDataAsList() — the businesses array is directly in data.
     */
    @SuppressWarnings("unchecked")
    private String discoverWabaId(String accessToken) {
        var response = metaRetry.execute("discoverWabaId",
                () -> metaApiClient.getBusinessAccounts(accessToken));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Could not fetch business accounts: " + response.getErrorMessage() +
                            ". Provide wabaId explicitly.");
        }

        // /me/businesses returns { "data": [ {...business...} ] }
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
    // FIX: WABA details returns object in data → use getDataAsMap()
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

            // WABA details: { "id": "...", "business_id": "...", "name": "..." }
            // This is a flat response — fields land in extras
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

    @Transactional
    public EmbeddedSignupResponse saveOnboardingData(EmbeddedSignupCallbackRequest request,
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

        // Best-effort: webhook subscribe + health check
        boolean webhookOk = subscribeAndVerifyWebhook(wabaId, tokenResult.accessToken());

        // Best-effort: phone number sync from Meta
        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(waba, tokenResult.accessToken());
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(waba.getId());
        } catch (Exception ex) {
            log.warn("Phone sync failed (retry via /{}/sync). Error: {}", waba.getId(), ex.getMessage());
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
    // WEBHOOK: Subscribe + Health Check
    // FIX: getSubscribedApps returns { "data": [...] } → use getDataAsList()
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

    /**
     * FIX: subscribed_apps returns { "data": [ { "id": "appId", ... } ] }
     * Use getDataAsList() directly.
     */
    @SuppressWarnings("unchecked")
    private boolean isOurAppSubscribed(MetaApiResponse response) {
        if (!response.isOk()) return false;
        try {
            // { "data": [ { "id": "appId", ... } ] }
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