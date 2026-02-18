package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.dto.response.SignupConfigResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.DuplicateWabaException;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import com.aigreentick.services.wabaaccounts.mapper.WabaMapper;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the complete Meta WhatsApp Embedded Signup flow.
 *
 * PHP → Java Migration Notes:
 * ─────────────────────────────────────────────────────────
 * PHP equivalent: FbEmbeddedSignUpController::signup() + signupWithoutPhoneNumber()
 *
 * Key improvements over PHP:
 *  - Token extension FAILS HARD (PHP also fails hard — our fallback was wrong)
 *  - Global WABA uniqueness check (PHP allows cross-tenant duplicates)
 *  - Configurable PIN (PHP hardcodes '123456')
 *  - Circuit breaker + retry on all Meta calls (PHP: raw HTTP, no resilience)
 *  - HMAC webhook signature verification (PHP: no signature check)
 *  - Token expiry stored and trackable (PHP: never stores expiry)
 *  - Consistent API version v21.0 (PHP mixes v18.0 and v23.0)
 *
 * SMB Sync (contacts + history) from PHP's signupWithoutPhoneNumber:
 *  Those calls are specific to the "coexistence" migration flow
 *  (user migrating from WhatsApp personal/SMB app). They are NOT needed
 *  for standard embedded signup. Only add them if you support migration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedSignupService {

    private final MetaApiClient metaApiClient;
    private final MetaApiConfig metaApiConfig;
    private final MetaOAuthAccountRepository oauthAccountRepository;
    private final WabaAccountRepository wabaAccountRepository;
    private final PhoneNumberService phoneNumberService;

    private static final String REQUIRED_SCOPES =
            "whatsapp_business_management,whatsapp_business_messaging,business_management";

    // Minimum acceptable token lifetime — 7 days
    // Anything less indicates a short-lived token was returned, which we must reject
    private static final long MIN_LONG_LIVED_SECONDS = 7L * 24 * 3600;

    // ============================================================
    // CONFIG
    // ============================================================

    public SignupConfigResponse getSignupConfig() {
        return SignupConfigResponse.builder()
                .metaAppId(metaApiConfig.getAppId())
                .apiVersion(metaApiConfig.getApiVersion())
                .scopes(REQUIRED_SCOPES)
                .extrasJson(buildExtrasJson())
                .callbackEndpoint("/api/v1/embedded-signup/callback")
                .configId(metaApiConfig.getEmbeddedSignupConfigId())
                .build();
    }

    // ============================================================
    // CALLBACK — Core onboarding transaction
    //
    // NOTE ON TRANSACTION BOUNDARY:
    // OAuth code exchange happens BEFORE the @Transactional boundary below
    // because the code is single-use — if the DB transaction rolls back
    // after a successful exchange, we've consumed the code permanently.
    // ============================================================

    public EmbeddedSignupResponse processSignupCallback(EmbeddedSignupCallbackRequest request) {
        log.info("Processing embedded signup: orgId={}, wabaId={}",
                request.getOrganizationId(), request.getWabaId());

        // ── Step A: Exchange auth code for access token ──────────────
        // OUTSIDE transaction — code is single-use, can't roll back
        String shortLivedToken = exchangeCodeForToken(request.getCode());
        log.info("Short-lived token obtained for orgId={}", request.getOrganizationId());

        // ── Step B: Extend to long-lived token ───────────────────────
        // FIX: This now fails HARD if extension fails.
        // PHP does the same — it returns 400 if long-lived token fails.
        // We were incorrectly more lenient than PHP here.
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);
        log.info("Long-lived token obtained. expiresIn={}s (~{} days)",
                tokenResult.expiresIn(), tokenResult.expiresIn() / 86400);

        // ── Step C: Resolve WABA ID ──────────────────────────────────
        String wabaId = resolveWabaId(request, tokenResult.accessToken());

        // ── NOW: Enter DB transaction for all persistence ────────────
        return saveOnboardingData(request, tokenResult, wabaId);
    }

    /**
     * All DB operations in a single transaction.
     * Meta API calls (webhook subscription, phone sync) are best-effort after save.
     */
    @Transactional
    public EmbeddedSignupResponse saveOnboardingData(EmbeddedSignupCallbackRequest request,
                                                     TokenResult tokenResult,
                                                     String wabaId) {

        // ── Step D-1: Global WABA uniqueness check ───────────────────
        // FIX: Check globally first — prevents cross-tenant WABA conflicts.
        // PHP only checks per org. This is the bigger bug.
        if (wabaAccountRepository.existsByWabaId(wabaId)) {
            // If it belongs to THIS org, give a specific message
            if (wabaAccountRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), wabaId)) {
                log.warn("Duplicate WABA for same org: orgId={}, wabaId={}",
                        request.getOrganizationId(), wabaId);
                throw DuplicateWabaException.forOrganization(request.getOrganizationId(), wabaId);
            }
            // Belongs to a DIFFERENT org — this is a cross-tenant conflict
            log.error("CRITICAL: Cross-tenant WABA conflict detected: wabaId={} already belongs to another org. Attempted by orgId={}",
                    wabaId, request.getOrganizationId());
            throw new DuplicateWabaException(
                    "This WhatsApp Business Account is already connected to another account. " +
                            "If you believe this is an error, contact support."
            );
        }

        // ── Step E: Persist OAuth account ───────────────────────────
        String businessManagerId = resolveBusinessManagerId(request, wabaId, tokenResult.accessToken());
        MetaOAuthAccount oauthAccount = saveOAuthAccount(
                request.getOrganizationId(),
                businessManagerId,
                tokenResult.accessToken(),
                tokenResult.expiresIn()
        );

        // ── Step F: Create WABA record ───────────────────────────────
        WabaAccount waba = WabaAccount.builder()
                .organizationId(request.getOrganizationId())
                .metaOAuthAccountId(oauthAccount.getId())
                .wabaId(wabaId)
                .status(WabaStatus.ACTIVE)
                .build();
        waba = wabaAccountRepository.save(waba);
        log.info("WABA created: id={}, wabaId={}", waba.getId(), wabaId);

        // ── Step G: Subscribe WABA to webhook (best-effort) ──────────
        // Same as PHP — fails silently so it doesn't block onboarding
        boolean webhookSubscribed = subscribeToWebhook(wabaId, tokenResult.accessToken());

        // ── Step H: Sync phone numbers (best-effort) ─────────────────
        // Same as PHP — phone sync failure never blocks onboarding
        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(waba, tokenResult.accessToken());
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(waba.getId());
            log.info("Phone numbers synced: count={}, wabaId={}", phoneNumbers.size(), wabaId);
        } catch (Exception ex) {
            log.warn("Phone sync failed during signup (retry via /sync). Error: {}", ex.getMessage());
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
                .webhookSubscribed(webhookSubscribed)
                .summary(buildSummary(phoneNumbers.size(), wabaId))
                .build();
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private String exchangeCodeForToken(String code) {
        var metaResponse = metaApiClient.exchangeCodeForToken(code);
        if (!Boolean.TRUE.equals(metaResponse.getSuccess()) || metaResponse.getData() == null) {
            throw new InvalidRequestException("Failed to exchange OAuth code — code may be expired or invalid");
        }

        Object tokenObj = metaResponse.getData().get("access_token");
        if (tokenObj == null || tokenObj.toString().isBlank()) {
            throw new InvalidRequestException("Meta returned empty access token during code exchange");
        }

        return tokenObj.toString();
    }

    /**
     * FIX: Token extension now FAILS HARD if Meta doesn't return a long-lived token.
     *
     * Why: A short-lived token (1 hour) would cause:
     *  - Webhook delivery failures after 1 hour
     *  - Phone sync failures
     *  - Message sending failures
     *  - Customer thinks the product is broken
     *
     * PHP behavior: Also fails hard (returns 400 if long-lived token request fails).
     * Our previous "fallback" was actually WORSE than PHP.
     *
     * Root cause of short-lived token: Meta App is in Development mode, not Live mode.
     * Only Live mode apps can issue 60-day tokens via fb_exchange_token.
     */
    private TokenResult extendToLongLivedToken(String shortLivedToken) {
        try {
            var extendResponse = metaApiClient.extendAccessToken(shortLivedToken);

            if (!Boolean.TRUE.equals(extendResponse.getSuccess()) || extendResponse.getData() == null) {
                throw new InvalidRequestException(
                        "Meta did not return a long-lived token. " +
                                "Ensure your Meta App is in Live mode (not Development mode) at " +
                                "developers.facebook.com → your app → App Mode.");
            }

            Map<String, Object> data = extendResponse.getData();
            String longToken = String.valueOf(data.getOrDefault("access_token", ""));

            if (longToken.isBlank() || longToken.equals("null")) {
                throw new InvalidRequestException("Meta returned a blank access token during extension");
            }

            long expiresIn = parseLong(data.getOrDefault("expires_in", 0L));

            // Validate we actually got a long-lived token (> 7 days)
            // If Meta returns < 7 days, something is wrong (Dev mode, wrong grant type, etc.)
            if (expiresIn < MIN_LONG_LIVED_SECONDS) {
                throw new InvalidRequestException(String.format(
                        "Meta returned a short-lived token (expires in %d seconds, ~%d hours). " +
                                "Production onboarding requires a long-lived token (60 days). " +
                                "Check that your Meta App is in Live mode.",
                        expiresIn, expiresIn / 3600));
            }

            log.info("Long-lived token validated: expires in {} seconds (~{} days)",
                    expiresIn, expiresIn / 86400);
            return new TokenResult(longToken, expiresIn, true);

        } catch (InvalidRequestException ex) {
            // Re-throw our own validation exceptions as-is
            throw ex;
        } catch (MetaApiException ex) {
            // Meta API errors during extension — fail with clear message
            throw new InvalidRequestException(
                    "Failed to extend token to long-lived: " + ex.getMessage() +
                            ". Ensure META_APP_ID and META_APP_SECRET are correct.");
        }
    }

    private String resolveWabaId(EmbeddedSignupCallbackRequest request, String accessToken) {
        if (request.getWabaId() != null && !request.getWabaId().isBlank()) {
            return request.getWabaId();
        }
        log.info("wabaId not in request — auto-discovering from Meta token");
        return discoverWabaId(accessToken);
    }

    @SuppressWarnings("unchecked")
    private String discoverWabaId(String accessToken) {
        var response = metaApiClient.getBusinessAccounts(accessToken);

        if (!Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) {
            throw new InvalidRequestException(
                    "Could not discover WABA ID from Meta. Please provide wabaId explicitly.");
        }

        List<Map<String, Object>> businesses =
                (List<Map<String, Object>>) response.getData().get("data");

        if (businesses == null || businesses.isEmpty()) {
            throw new InvalidRequestException(
                    "No Business Manager accounts found for this token. " +
                            "Ensure the user completed the embedded signup flow.");
        }

        for (Map<String, Object> business : businesses) {
            Map<String, Object> wabaData =
                    (Map<String, Object>) business.get("owned_whatsapp_business_accounts");
            if (wabaData != null) {
                List<Map<String, Object>> wabas =
                        (List<Map<String, Object>>) wabaData.get("data");
                if (wabas != null && !wabas.isEmpty()) {
                    String wabaId = String.valueOf(wabas.get(0).get("id"));
                    log.info("WABA auto-discovered: wabaId={}", wabaId);
                    return wabaId;
                }
            }
        }

        throw new InvalidRequestException(
                "No WABA found in Business Manager. " +
                        "Ensure the user completed WhatsApp Business Account setup.");
    }

    @SuppressWarnings("unchecked")
    private String resolveBusinessManagerId(EmbeddedSignupCallbackRequest request,
                                            String wabaId, String accessToken) {
        if (request.getBusinessManagerId() != null && !request.getBusinessManagerId().isBlank()) {
            return request.getBusinessManagerId();
        }

        try {
            var wabaDetails = metaApiClient.getWabaDetails(wabaId, accessToken);
            if (Boolean.TRUE.equals(wabaDetails.getSuccess()) && wabaDetails.getData() != null) {
                Object bizId = wabaDetails.getData().get("business_id");
                if (bizId != null) return String.valueOf(bizId);
            }
        } catch (Exception ex) {
            log.warn("Could not resolve business manager ID from WABA details: {}", ex.getMessage());
        }

        return "UNKNOWN-" + wabaId;
    }

    private MetaOAuthAccount saveOAuthAccount(Long organizationId, String businessManagerId,
                                              String accessToken, long expiresInSeconds) {
        return oauthAccountRepository
                .findByOrganizationIdAndBusinessManagerId(organizationId, businessManagerId)
                .map(existing -> {
                    existing.setAccessToken(accessToken);
                    existing.setExpiresAt(expiresInSeconds > 0
                            ? LocalDateTime.now().plusSeconds(expiresInSeconds)
                            : null);
                    log.info("OAuth token refreshed for orgId={}, bm={}", organizationId, businessManagerId);
                    return oauthAccountRepository.save(existing);
                })
                .orElseGet(() -> {
                    MetaOAuthAccount newAccount = MetaOAuthAccount.builder()
                            .organizationId(organizationId)
                            .businessManagerId(businessManagerId)
                            .accessToken(accessToken)
                            .expiresAt(expiresInSeconds > 0
                                    ? LocalDateTime.now().plusSeconds(expiresInSeconds)
                                    : null)
                            .build();
                    log.info("New OAuth account created for orgId={}, bm={}", organizationId, businessManagerId);
                    return oauthAccountRepository.save(newAccount);
                });
    }

    private boolean subscribeToWebhook(String wabaId, String accessToken) {
        try {
            var subResult = metaApiClient.subscribeWabaToWebhook(wabaId, accessToken);
            boolean success = Boolean.TRUE.equals(subResult.getSuccess());
            if (success) {
                log.info("Webhook subscription successful: wabaId={}", wabaId);
            } else {
                log.warn("Webhook subscription returned non-success for wabaId={}", wabaId);
            }
            return success;
        } catch (Exception ex) {
            log.warn("Webhook subscription failed (non-fatal): wabaId={}, error={}", wabaId, ex.getMessage());
            return false;
        }
    }

    private String buildExtrasJson() {
        return "{" +
                "\"feature\": \"whatsapp_embedded_signup\", " +
                "\"version\": 2, " +
                "\"sessionInfoVersion\": \"3\"" +
                "}";
    }

    private String buildSummary(int phoneCount, String wabaId) {
        if (phoneCount == 0) {
            return "WhatsApp Business Account connected (WABA: " + wabaId + "). " +
                    "No phone numbers found — add them in Meta Business Manager.";
        }
        return "WhatsApp Business Account connected with " + phoneCount +
                " phone number" + (phoneCount > 1 ? "s" : "") + ". Ready to send messages!";
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

    /** Package-private for testability */
    record TokenResult(String accessToken, long expiresIn, boolean isLongLived) {}
}