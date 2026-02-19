package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Meta Graph API Client — all calls to Meta's Graph API in one place.
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
@RequiredArgsConstructor
@Slf4j
public class MetaApiClient {

    private final MetaApiConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

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
    // These methods were missing — caused PhoneNumberService to fail to compile
    // ════════════════════════════════════════════════════════════

    /**
     * Register a phone number with Meta WhatsApp Cloud API.
     *
     * POST /{phoneNumberId}/register
     * Body: messaging_product=whatsapp&pin={6-digit-2fa-pin}
     *
     * Prerequisites:
     *   - Phone number must exist in the WABA
     *   - User must have already set a 2-step verification PIN via WhatsApp
     *   - WABA must be in ACTIVE status
     *
     * On success: phone number transitions to CONNECTED state in Meta's system.
     * Returns: { "success": true }
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
     *
     * POST /{phoneNumberId}/request_code
     * Body: code_method={SMS|VOICE}&language={locale}
     *
     * Meta sends an OTP to the phone number.
     * The customer then provides that OTP to be passed into verifyCode().
     *
     * Returns: { "success": true }
     */
    public MetaApiResponse requestVerificationCode(String phoneNumberId,
                                                   String accessToken,
                                                   String method,
                                                   String locale) {
        log.info("Requesting verification code: phoneNumberId={}, method={}", phoneNumberId, method);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code_method", method);   // "SMS" or "VOICE"
        body.add("language",   locale);    // e.g. "en_US"
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/request_code", body, accessToken);
    }

    /**
     * Verify a phone number with the OTP code received.
     *
     * POST /{phoneNumberId}/verify_code
     * Body: code={6-digit-otp}
     *
     * On success: phone number is verified in Meta's system.
     * Returns: { "success": true }
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
        body.add("business_app",   appId);
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
     *
     * GET /debug_token?input_token={token}&access_token={appId}|{appSecret}
     *
     * The access_token param uses app access token format (appId|appSecret).
     * This does NOT require a user token — uses your app credentials directly.
     *
     * Response includes:
     *   - is_valid: false if token is revoked, expired, or invalid
     *   - expires_at: Unix timestamp of expiry (0 = permanent)
     *   - scopes: list of granted permissions
     *   - user_id: Meta user ID who authorized the token
     *
     * Use this in your token health scheduler to detect:
     *   - Revoked tokens (user logged out of Meta, or revoked app access)
     *   - Expired user tokens (60-day window missed)
     *   - Missing scopes (user re-authorized with fewer permissions)
     */
    public MetaApiResponse debugToken(String tokenToInspect) {
        log.debug("Inspecting token validity via /debug_token");
        // App access token = {app_id}|{app_secret}
        String appAccessToken = config.getAppId() + "|" + config.getAppSecret();
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/debug_token")
                .queryParam("input_token",  tokenToInspect)
                .queryParam("access_token", appAccessToken)
                .toUriString();
        return get(url);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE — HTTP helpers
    // ════════════════════════════════════════════════════════════

    private MetaApiResponse get(String url) {
        try {
            ResponseEntity<MetaApiResponse> response =
                    restTemplate.getForEntity(url, MetaApiResponse.class);
            return handleResponse(response);
        } catch (HttpClientErrorException ex) {
            return parseErrorResponse(ex);
        } catch (HttpServerErrorException ex) {
            throw new MetaApiException("Meta API server error: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new MetaApiException("Failed to call Meta API: " + ex.getMessage(), ex);
        }
    }

    private MetaApiResponse post(String url,
                                 MultiValueMap<String, String> formBody,
                                 String bearerToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (bearerToken != null) {
                headers.setBearerAuth(bearerToken);
            }
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formBody, headers);

            ResponseEntity<MetaApiResponse> response =
                    restTemplate.postForEntity(url, entity, MetaApiResponse.class);
            return handleResponse(response);
        } catch (HttpClientErrorException ex) {
            return parseErrorResponse(ex);
        } catch (HttpServerErrorException ex) {
            throw new MetaApiException("Meta API server error: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new MetaApiException("Failed to call Meta API: " + ex.getMessage(), ex);
        }
    }

    private MetaApiResponse handleResponse(ResponseEntity<MetaApiResponse> response) {
        MetaApiResponse body = response.getBody();
        if (body == null) {
            MetaApiResponse empty = new MetaApiResponse();
            empty.setSuccess(response.getStatusCode().is2xxSuccessful());
            return empty;
        }
        if (body.getSuccess() == null && response.getStatusCode().is2xxSuccessful()) {
            body.setSuccess(true);
        }
        return body;
    }

    private MetaApiResponse parseErrorResponse(HttpClientErrorException ex) {
        try {
            MetaApiResponse errorResponse = objectMapper.readValue(
                    ex.getResponseBodyAsString(), MetaApiResponse.class);
            errorResponse.setSuccess(false);
            log.warn("Meta API client error {}: {}", ex.getStatusCode(), errorResponse.getErrorMessage());
            return errorResponse;
        } catch (Exception parseEx) {
            log.warn("Meta API error (unparseable): status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            MetaApiResponse fallback = new MetaApiResponse();
            fallback.setSuccess(false);
            return fallback;
        }
    }
}