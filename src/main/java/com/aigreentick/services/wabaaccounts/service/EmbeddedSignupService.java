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
 * This is the CORE onboarding flow — exactly how WATI, AiSensy, Interakt
 * and other WhatsApp SaaS platforms onboard their customers.
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * COMPLETE EMBEDDED SIGNUP FLOW:
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 1. Frontend loads → GET /api/v1/embedded-signup/config
 *    Gets Meta App ID + scopes needed to initialize FB SDK
 *
 * 2. User clicks "Connect WhatsApp Business"
 *    FB.login() opens Meta's guided signup popup
 *    User: selects/creates Business Manager → selects/creates WABA
 *          → selects phone number → grants permissions
 *
 * 3. Meta calls your frontend callback with:
 *    { authResponse: { code, wabaId, businessId } }
 *
 * 4. Frontend → POST /api/v1/embedded-signup/callback
 *    with { organizationId, code, wabaId, businessManagerId }
 *
 * 5. Backend (this service):
 *    a. Exchange code → short-lived user access token
 *    b. Extend to long-lived token (60 days) for production use
 *    c. Save MetaOAuthAccount with long-lived token
 *    d. Fetch WABA details from Meta (verify it's accessible)
 *    e. Create WabaAccount in DB (ACTIVE status)
 *    f. Subscribe WABA to webhook events
 *    g. Sync phone numbers from Meta → save as WabaPhoneNumbers
 *    h. Return EmbeddedSignupResponse with full WABA + phone details
 *
 * 6. Frontend shows success dashboard with connected WABA + phones
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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

    // Required OAuth scopes for full WhatsApp Business API access
    private static final String REQUIRED_SCOPES =
            "whatsapp_business_management,whatsapp_business_messaging,business_management";

    // ============================================================
    // CONFIG — Step 1: Give frontend what it needs to start signup
    // ============================================================

    /**
     * Returns SDK configuration for the frontend to initialize FB Login.
     * Called once when the Connect WhatsApp page loads.
     */
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
    // CALLBACK — Steps 2–8: Full signup processing
    // ============================================================

    /**
     * Processes the OAuth callback from the frontend after Meta signup.
     * This is the main onboarding transaction.
     *
     * @param request Contains the auth code + wabaId from Meta SDK
     * @return Full WABA details with phone numbers — ready for dashboard
     */
    @Transactional
    public EmbeddedSignupResponse processSignupCallback(EmbeddedSignupCallbackRequest request) {
        log.info("Processing embedded signup: orgId={}, wabaId={}",
                request.getOrganizationId(), request.getWabaId());

        // ── Step A: Exchange auth code for access token ─────────────
        String shortLivedToken = exchangeCodeForToken(request.getCode());
        log.info("Got short-lived token for orgId={}", request.getOrganizationId());

        // ── Step B: Extend to long-lived token (60 days) ────────────
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);
        log.info("Token extended. expiresIn={}s, longLived={}",
                tokenResult.expiresIn(), tokenResult.isLongLived());

        // ── Step C: Resolve WABA ID ──────────────────────────────────
        // Prefer wabaId from SDK response; fallback: discover from token
        String wabaId = resolveWabaId(request, tokenResult.accessToken());

        // ── Step D: Validate no duplicate WABA for this org ─────────
        if (wabaAccountRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), wabaId)) {
            log.warn("Duplicate WABA signup attempt: orgId={}, wabaId={}",
                    request.getOrganizationId(), wabaId);
            throw DuplicateWabaException.forOrganization(request.getOrganizationId(), wabaId);
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

        // ── Step G: Subscribe WABA to webhook events ─────────────────
        boolean webhookSubscribed = subscribeToWebhook(wabaId, tokenResult.accessToken());

        // ── Step H: Sync phone numbers from Meta ─────────────────────
        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(waba, tokenResult.accessToken());
            // Reload to get the saved phone numbers
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(waba.getId());
            log.info("Phone numbers synced: count={}, wabaId={}", phoneNumbers.size(), wabaId);
        } catch (Exception ex) {
            // Phone sync failure must NOT fail onboarding — it can be retried
            log.warn("Phone sync failed during signup (can retry via /sync). Error: {}", ex.getMessage());
        }

        // ── Step I: Build success response ───────────────────────────
        String summary = buildSummary(phoneNumbers.size(), wabaId);

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
                .summary(summary)
                .build();
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    /**
     * Step A: Exchange OAuth code for short-lived user access token.
     * Code is valid for ~10 minutes — must be exchanged immediately.
     */
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
     * Step B: Try to extend short-lived token to long-lived (60 days).
     * Falls back to short-lived token if extension fails (non-critical).
     */
    private TokenResult extendToLongLivedToken(String shortLivedToken) {
        try {
            var extendResponse = metaApiClient.extendAccessToken(shortLivedToken);

            if (Boolean.TRUE.equals(extendResponse.getSuccess()) && extendResponse.getData() != null) {
                Map<String, Object> data = extendResponse.getData();
                String longToken = String.valueOf(data.getOrDefault("access_token", shortLivedToken));
                long expiresIn = parseLong(data.getOrDefault("expires_in", 3600L));
                boolean isLong = expiresIn > 7 * 24 * 3600; // > 7 days = long-lived

                return new TokenResult(longToken, expiresIn, isLong);
            }
        } catch (MetaApiException ex) {
            log.warn("Token extension failed — using short-lived token. Error: {}", ex.getMessage());
        }

        // Fallback: use short-lived token (1 hour)
        return new TokenResult(shortLivedToken, 3600L, false);
    }

    /**
     * Step C: Resolve WABA ID.
     * Prefer the one from SDK (more reliable); auto-discover if missing.
     */
    private String resolveWabaId(EmbeddedSignupCallbackRequest request, String accessToken) {
        if (request.getWabaId() != null && !request.getWabaId().isBlank()) {
            return request.getWabaId();
        }

        log.info("wabaId not in request — auto-discovering from Meta token");
        return discoverWabaId(accessToken);
    }

    /**
     * Auto-discover WABA ID by calling /me/businesses → owned_whatsapp_business_accounts.
     * Used when SDK doesn't provide wabaId directly.
     */
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

        // Take the first business's first WABA — in embedded signup context
        // the user selects exactly one WABA
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
                "No WABA found in your Business Manager. " +
                        "Please complete the WhatsApp Business Account setup in Meta Business Manager.");
    }

    /**
     * Resolve Business Manager ID — from request or WABA details.
     */
    @SuppressWarnings("unchecked")
    private String resolveBusinessManagerId(EmbeddedSignupCallbackRequest request,
                                            String wabaId, String accessToken) {
        if (request.getBusinessManagerId() != null && !request.getBusinessManagerId().isBlank()) {
            return request.getBusinessManagerId();
        }

        // Fetch WABA details to get business ID
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

    /**
     * Step E: Save or update MetaOAuthAccount.
     * If same org+BM already has an account, refresh the token.
     */
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

    /**
     * Step G: Subscribe WABA to webhook (best effort — don't fail signup).
     */
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
            return 3600L; // default 1 hour
        }
    }

    /**
     * Returns true if the organization has at least one ACTIVE WABA.
     */
    public boolean isOrganizationConnected(Long organizationId) {
        return !wabaAccountRepository
                .findByOrganizationIdAndStatus(organizationId, WabaStatus.ACTIVE)
                .isEmpty();
    }

    /** Immutable value object for token exchange result */
    private record TokenResult(String accessToken, long expiresIn, boolean isLongLived) {}
}