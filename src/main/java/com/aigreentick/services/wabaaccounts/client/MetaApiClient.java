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
 * ══════════════════════════════════════════════════════════════════
 * Meta Graph API Client
 * ══════════════════════════════════════════════════════════════════
 *
 * Centralises ALL calls to Meta's Graph API in one place.
 * Each method maps to exactly one Meta endpoint.
 *
 * Design rules:
 *   1. Never throw checked exceptions — wrap in MetaApiException
 *   2. Never log access tokens, app secrets, or permanent tokens
 *   3. All methods return MetaApiResponse — callers decide what to do
 *   4. appsecret_proof is the caller's responsibility to compute
 *
 * Phase 1 methods (EmbeddedSignupService):
 *   - exchangeCodeForToken()        POST /oauth/access_token
 *   - extendAccessToken()           GET  /oauth/access_token?grant_type=fb_exchange_token
 *   - getWabaDetails()              GET  /{wabaId}?fields=id,name,business_id
 *   - getSharedWabas()              GET  /me/businesses + /{bizId}/owned_whatsapp_business_accounts
 *   - getPhoneNumbers()             GET  /{wabaId}/phone_numbers
 *   - subscribeWabaWebhook()        POST /{wabaId}/subscribed_apps
 *
 * Phase 2 methods (SystemUserProvisioningService):
 *   - createSystemUser()            POST /{businessId}/system_users
 *   - assignWabaToSystemUser()      POST /{wabaId}/assigned_users
 *   - generateSystemUserToken()     POST /{systemUserId}/access_tokens
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
     * Exchange short-lived OAuth code for a short-lived user access token.
     * POST /oauth/access_token
     */
    public MetaApiResponse exchangeCodeForToken(String code) {
        log.debug("Exchanging OAuth code for user token");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/oauth/access_token")
                .queryParam("client_id",     config.getAppId())
                .queryParam("client_secret", config.getAppSecret())
                .queryParam("code",          code)
                .queryParam("grant_type",    "authorization_code")
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
     * Get WABA details including the owning Business Manager ID.
     * GET /{wabaId}?fields=id,name,currency,timezone_id,business_id,message_template_namespace
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

    /**
     * Alias used by EmbeddedSignupService for WABA discovery.
     * Delegates to getUserBusinesses().
     */
    public MetaApiResponse getBusinessAccounts(String accessToken) {
        return getUserBusinesses(accessToken);
    }

    /**
     * Get all OAuth permissions granted by the user for this token.
     *
     * GET /me/permissions
     *
     * Response: { "data": [ { "permission": "whatsapp_business_management", "status": "granted" }, ... ] }
     *
     * Use after token exchange to verify ALL required scopes were granted.
     * Users can de-select scopes during the OAuth dialog — never assume they granted everything.
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
     * Get the currently subscribed apps for a WABA (webhook health check).
     *
     * GET /{wabaId}/subscribed_apps
     *
     * Use AFTER subscribeWabaWebhook() to confirm the subscription is active.
     * subscribeWabaWebhook() returning success: true ≠ webhook is working.
     * This verifies your app actually appears in the subscription list.
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
     * Get WABAs shared via embedded signup (connected by the user).
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
    // PHASE 2 — System User Provisioning
    // ════════════════════════════════════════════════════════════

    /**
     * Step 1 — Create a System User inside a Business Manager.
     *
     * POST /{businessId}/system_users
     * Body: name={name}&role={role}&access_token={token}
     *
     * Roles: "ADMIN" | "EMPLOYEE"
     *   ADMIN  → can assign assets, generate tokens, manage permissions
     *   EMPLOYEE → limited access, cannot manage permissions
     *
     * We always use ADMIN so the system user can manage WABAs.
     * If a system user with the same name already exists, Meta returns
     * the existing one (effectively idempotent).
     *
     * Returns: { "id": "systemUserId" }
     */
    public MetaApiResponse createSystemUser(String businessId,
                                            String name,
                                            String role,
                                            String accessToken) {
        log.info("Creating system user in Business Manager: businessId={}, name={}, role={}",
                businessId, name, role);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("name",         name);
        body.add("role",         role);
        body.add("access_token", accessToken);

        return post(config.getVersionedBaseUrl() + "/" + businessId + "/system_users", body, null);
    }

    /**
     * Step 2 — Assign a WABA to the System User with MANAGE permissions.
     *
     * POST /{wabaId}/assigned_users
     * Body: user={systemUserId}&tasks=["MANAGE"]&access_token={token}
     *
     * Tasks available:
     *   MANAGE         → full access: send messages, manage templates, manage settings
     *   DEVELOP        → for developers to test
     *   ANALYZE        → read-only analytics access
     *
     * We use MANAGE — the system user must be able to send messages and
     * manage templates on behalf of the customer's WABA.
     *
     * Returns: { "success": true }
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
     *
     * POST /{systemUserId}/access_tokens
     * Body: business_app={appId}&appsecret_proof={hmac}&scope={scopes}&access_token={token}
     *
     * appsecret_proof:
     *   HMAC-SHA256(currentAccessToken, appSecret)
     *   Meta requires this to prove you know the app secret.
     *   Prevents stolen tokens from being used to issue more tokens.
     *   Compute with: SystemUserProvisioningService.computeAppSecretProof()
     *
     * scope:
     *   Comma-separated list of permissions the system user token will have.
     *   Must be a subset of permissions already granted to your Meta App.
     *
     * Returns: { "access_token": "EAAxxxPERMANENTTOKENxxx...", "token_type": "bearer" }
     *
     * The returned token NEVER EXPIRES. Store it as the primary token.
     */
    public MetaApiResponse generateSystemUserToken(String systemUserId,
                                                   String appId,
                                                   String appSecretProof,
                                                   String scope,
                                                   String accessToken) {
        log.info("Generating permanent token for system user: {}", systemUserId);
        // NOTE: never log appSecretProof or the resulting access token
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("business_app",  appId);
        body.add("appsecret_proof", appSecretProof);
        body.add("scope",          scope);
        body.add("access_token",   accessToken);

        return post(config.getVersionedBaseUrl() + "/" + systemUserId + "/access_tokens", body, null);
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
                                 String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (accessToken != null) {
                headers.setBearerAuth(accessToken);
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
        // For endpoints that return { "success": true } — mark explicitly
        if (body.getSuccess() == null && response.getStatusCode().is2xxSuccessful()) {
            body.setSuccess(true);
        }
        return body;
    }

    /**
     * Parse the error body from a 4xx response into a MetaApiResponse.
     * This allows callers to inspect error.message / error.code
     * instead of catching exceptions for business logic decisions.
     */
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