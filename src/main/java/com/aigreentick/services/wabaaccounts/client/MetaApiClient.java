package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Meta Graph API Client — all calls to Meta's Graph API in one place.
 *
 * Uses WebClient (non-blocking) instead of RestTemplate.
 * Calls are made synchronously via .block() so service layer stays unchanged.
 *
 * Design rules:
 *   1. Never throw checked exceptions — wrap in MetaApiException
 *   2. Never log access tokens, app secrets, or permanent tokens
 *   3. All methods return MetaApiResponse — callers decide what to do
 *
 * Phase 1 (EmbeddedSignupService):
 *   - exchangeCodeForToken()        POST /oauth/access_token
 *   - extendAccessToken()           GET  /oauth/access_token?grant_type=fb_exchange_token
 *   - getWabaDetails()              GET  /{wabaId}?fields=...
 *   - getSharedWabas()              GET  /{bizId}/client_whatsapp_business_accounts
 *   - getPhoneNumbers()             GET  /{wabaId}/phone_numbers
 *   - subscribeWabaWebhook()        POST /{wabaId}/subscribed_apps
 *   - getUserPermissions()          GET  /me/permissions
 *   - getSubscribedApps()           GET  /{wabaId}/subscribed_apps
 *
 * Phase 1.5 (PhoneNumberService):
 *   - registerPhoneNumber()         POST /{phoneNumberId}/register
 *   - requestVerificationCode()     POST /{phoneNumberId}/request_code
 *   - verifyCode()                  POST /{phoneNumberId}/verify_code
 *
 * Phase 2 (SystemUserProvisioningService):
 *   - createSystemUser()            POST /{businessId}/system_users
 *   - assignWabaToSystemUser()      POST /{wabaId}/assigned_users
 *   - generateSystemUserToken()     POST /{systemUserId}/access_tokens
 *
 * Token Health (TokenHealthScheduler):
 *   - debugToken()                  GET  /debug_token
 */
@Component
@Slf4j
public class MetaApiClient {

    private final MetaApiConfig config;
    private final WebClient webClient;

    public MetaApiClient(MetaApiConfig config,
                         @Qualifier("metaWebClient") WebClient webClient) {
        this.config = config;
        this.webClient = webClient;
    }

    // ════════════════════════════════════════════════════════════
    // PHASE 1 — Token + WABA discovery
    // ════════════════════════════════════════════════════════════

    /**
     * Exchange short-lived OAuth code for user access token.
     * POST /oauth/access_token
     */
    public MetaApiResponse exchangeCodeForToken(String code) {
        log.info("Exchanging OAuth code for access token");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/oauth/access_token")
                .queryParam("code",          code)
                .queryParam("client_id",     config.getAppId())
                .queryParam("client_secret", config.getAppSecret())
                .toUriString();
        return get(url);
    }

    /**
     * Exchange short-lived token for long-lived user token (~60 days).
     * GET /oauth/access_token?grant_type=fb_exchange_token
     */
    public MetaApiResponse extendAccessToken(String shortLivedToken) {
        log.debug("Extending short-lived token to long-lived token");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/oauth/access_token")
                .queryParam("grant_type",        "fb_exchange_token")
                .queryParam("client_id",         config.getAppId())
                .queryParam("client_secret",     config.getAppSecret())
                .queryParam("fb_exchange_token", shortLivedToken)
                .toUriString();
        return get(url);
    }

    /**
     * Get WABA details including Business Manager ID.
     * GET /{wabaId}?fields=id,name,currency,timezone_id,business_id,...
     */
    public MetaApiResponse getWabaDetails(String wabaId, String accessToken) {
        log.debug("Fetching WABA details: wabaId={}", wabaId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + wabaId)
                .queryParam("fields",       "id,name,currency,timezone_id,business_id,message_template_namespace")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /**
     * Get all businesses the user has access to.
     * GET /me/businesses
     */
    public MetaApiResponse getUserBusinesses(String accessToken) {
        log.debug("Fetching user businesses");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/me/businesses")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /** Alias for getUserBusinesses — used by EmbeddedSignupService */
    public MetaApiResponse getBusinessAccounts(String accessToken) {
        return getUserBusinesses(accessToken);
    }

    /**
     * Get all OAuth permissions granted by the user's token.
     * GET /me/permissions
     */
    public MetaApiResponse getUserPermissions(String accessToken) {
        log.debug("Fetching token permissions");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/me/permissions")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /**
     * Get currently subscribed apps for a WABA (webhook health check).
     * GET /{wabaId}/subscribed_apps
     */
    public MetaApiResponse getSubscribedApps(String wabaId, String accessToken) {
        log.debug("Checking subscribed apps for WABA: {}", wabaId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + wabaId + "/subscribed_apps")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /**
     * Get WABAs shared via embedded signup.
     * GET /{businessId}/client_whatsapp_business_accounts
     */
    public MetaApiResponse getSharedWabas(String businessId, String accessToken) {
        log.debug("Fetching shared WABAs for business: {}", businessId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + businessId + "/client_whatsapp_business_accounts")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /**
     * Get phone numbers registered under a WABA.
     * GET /{wabaId}/phone_numbers
     */
    public MetaApiResponse getPhoneNumbers(String wabaId, String accessToken) {
        log.debug("Fetching phone numbers for WABA: {}", wabaId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + wabaId + "/phone_numbers")
                .queryParam("fields",       "id,display_phone_number,verified_name,quality_rating,status,code_verification_status")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    /**
     * Subscribe to webhook for a WABA.
     * POST /{wabaId}/subscribed_apps
     */
    public MetaApiResponse subscribeWabaWebhook(String wabaId, String accessToken) {
        log.debug("Subscribing to webhook for WABA: {}", wabaId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("access_token", accessToken);
        return post(config.getVersionedBaseUrl() + "/" + wabaId + "/subscribed_apps", body, null);
    }

    /** Alias used by EmbeddedSignupService */
    public MetaApiResponse subscribeWabaToWebhook(String wabaId, String accessToken) {
        return subscribeWabaWebhook(wabaId, accessToken);
    }

    // ════════════════════════════════════════════════════════════
    // PHASE 1.5 — Phone Number Registration & Verification
    // ════════════════════════════════════════════════════════════

    /**
     * Register a phone number with Meta WhatsApp Cloud API.
     * POST /{phoneNumberId}/register
     */
    public MetaApiResponse registerPhoneNumber(String phoneNumberId,
                                               String accessToken,
                                               String pin) {
        log.info("Registering phone number with Meta: phoneNumberId={}", phoneNumberId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("messaging_product", "whatsapp");
        body.add("pin",              pin);
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/register", body, accessToken);
    }

    /**
     * Request a verification code for a phone number via SMS or Voice call.
     * POST /{phoneNumberId}/request_code
     */
    public MetaApiResponse requestVerificationCode(String phoneNumberId,
                                                   String accessToken,
                                                   String method,
                                                   String locale) {
        log.info("Requesting verification code: phoneNumberId={}, method={}", phoneNumberId, method);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code_method", method);
        body.add("language",   locale);
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/request_code", body, accessToken);
    }

    /**
     * Verify a phone number with the OTP code received.
     * POST /{phoneNumberId}/verify_code
     */
    public MetaApiResponse verifyCode(String phoneNumberId,
                                      String accessToken,
                                      String code) {
        log.info("Verifying OTP for phone number: phoneNumberId={}", phoneNumberId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/verify_code", body, accessToken);
    }

    // ════════════════════════════════════════════════════════════
    // PHASE 2 — System User Provisioning
    // ════════════════════════════════════════════════════════════

    /**
     * Step 1 — Create a System User inside a Business Manager.
     * POST /{businessId}/system_users
     */
    public MetaApiResponse createSystemUser(String businessId,
                                            String name,
                                            String role,
                                            String accessToken) {
        log.info("Creating system user: businessId={}, name={}, role={}", businessId, name, role);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("name",         name);
        body.add("role",         role);
        body.add("access_token", accessToken);
        return post(config.getVersionedBaseUrl() + "/" + businessId + "/system_users", body, null);
    }

    /**
     * Step 2 — Assign a WABA to the System User with MANAGE permissions.
     * POST /{wabaId}/assigned_users
     */
    public MetaApiResponse assignWabaToSystemUser(String wabaId,
                                                  String systemUserId,
                                                  String accessToken) {
        log.info("Assigning WABA to system user: wabaId={}, systemUserId={}", wabaId, systemUserId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("user",         systemUserId);
        body.add("tasks",        "[\"MANAGE\"]");
        body.add("access_token", accessToken);
        return post(config.getVersionedBaseUrl() + "/" + wabaId + "/assigned_users", body, null);
    }

    /**
     * Step 3 — Generate a permanent access token for the System User.
     * POST /{systemUserId}/access_tokens
     */
    public MetaApiResponse generateSystemUserToken(String systemUserId,
                                                   String appId,
                                                   String appSecretProof,
                                                   String scope,
                                                   String accessToken) {
        log.info("Generating permanent token for system user: {}", systemUserId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("business_app",    appId);
        body.add("appsecret_proof", appSecretProof);
        body.add("scope",           scope);
        body.add("access_token",    accessToken);
        return post(config.getVersionedBaseUrl() + "/" + systemUserId + "/access_tokens", body, null);
    }

    // ════════════════════════════════════════════════════════════
    // TOKEN HEALTH — Used by TokenHealthScheduler
    // ════════════════════════════════════════════════════════════

    /**
     * Inspect a token's validity, expiry, and granted scopes.
     * GET /debug_token?input_token={token}&access_token={appId}|{appSecret}
     */
    public MetaApiResponse debugToken(String tokenToInspect) {
        log.debug("Inspecting token validity via /debug_token");
        String appAccessToken = config.getAppId() + "|" + config.getAppSecret();
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/debug_token")
                .queryParam("input_token",  tokenToInspect)
                .queryParam("access_token", appAccessToken)
                .toUriString();
        return get(url);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE — HTTP helpers (WebClient, called synchronously via .block())
    // ════════════════════════════════════════════════════════════

    private MetaApiResponse get(String url) {
        try {
            MetaApiResponse response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(MetaApiResponse.class)
                    .block();

            return response != null ? response : emptySuccess();

        } catch (WebClientResponseException ex) {
            // 4xx / 5xx from Meta
            return parseErrorResponse(ex);
        } catch (Exception ex) {
            throw new MetaApiException("Failed to call Meta API: " + ex.getMessage(), ex);
        }
    }

    private MetaApiResponse post(String url,
                                 MultiValueMap<String, String> formBody,
                                 String bearerToken) {
        try {
            var requestSpec = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);

            if (bearerToken != null) {
                requestSpec = requestSpec.headers(h -> h.setBearerAuth(bearerToken));
            }

            MetaApiResponse response = requestSpec
                    .body(BodyInserters.fromFormData(formBody))
                    .retrieve()
                    .bodyToMono(MetaApiResponse.class)
                    .block();

            return response != null ? response : emptySuccess();

        } catch (WebClientResponseException ex) {
            return parseErrorResponse(ex);
        } catch (Exception ex) {
            throw new MetaApiException("Failed to call Meta API: " + ex.getMessage(), ex);
        }
    }

    private MetaApiResponse emptySuccess() {
        MetaApiResponse r = new MetaApiResponse();
        r.setSuccess(true);
        return r;
    }

    private MetaApiResponse parseErrorResponse(WebClientResponseException ex) {
        try {
            // Try to deserialize Meta's error JSON body
            String body = ex.getResponseBodyAsString();
            log.warn("Meta API error {}: {}", ex.getStatusCode(), body);

            // If it's a server error (5xx), throw so retry logic can handle it
            if (ex.getStatusCode().is5xxServerError()) {
                throw new MetaApiException("Meta API server error: " + ex.getMessage(), ex);
            }

            // For 4xx, return as a failed MetaApiResponse so callers can inspect it
            MetaApiResponse fallback = new MetaApiResponse();
            fallback.setSuccess(false);
            // Populate the error map so getErrorMessage() works
            fallback.setExtra("error", Map.of(
                    "message", ex.getMessage(),
                    "code",    ex.getStatusCode().value()
            ));
            return fallback;

        } catch (MetaApiException metaEx) {
            throw metaEx;
        } catch (Exception parseEx) {
            MetaApiResponse fallback = new MetaApiResponse();
            fallback.setSuccess(false);
            return fallback;
        }
    }
}