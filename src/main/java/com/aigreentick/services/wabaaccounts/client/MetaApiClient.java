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

    // ═══════════════════════════════════════════════════════════
    // PHASE 1 — Token + WABA discovery (v23.0)
    // ═══════════════════════════════════════════════════════════

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

    public MetaApiResponse getWabaDetails(String wabaId, String accessToken) {
        log.debug("Fetching WABA details: wabaId={}", wabaId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + wabaId)
                .queryParam("fields",       "id,name,currency,timezone_id,business_id,message_template_namespace")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    public MetaApiResponse getUserBusinesses(String accessToken) {
        log.debug("Fetching user businesses");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/me/businesses")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    public MetaApiResponse getBusinessAccounts(String accessToken) {
        return getUserBusinesses(accessToken);
    }

    public MetaApiResponse getUserPermissions(String accessToken) {
        log.debug("Fetching token permissions");
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/me/permissions")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    public MetaApiResponse getSubscribedApps(String wabaId, String accessToken) {
        log.debug("Checking subscribed apps for WABA: {}", wabaId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + wabaId + "/subscribed_apps")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

    public MetaApiResponse getSharedWabas(String businessId, String accessToken) {
        log.debug("Fetching shared WABAs for business: {}", businessId);
        String url = UriComponentsBuilder
                .fromUriString(config.getVersionedBaseUrl() + "/" + businessId + "/client_whatsapp_business_accounts")
                .queryParam("access_token", accessToken)
                .toUriString();
        return get(url);
    }

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
     * FIX 1: Added subscribed_fields=['messages'] — without this Meta silently
     *        ignores the subscription and incoming messages NEVER arrive.
     * FIX 2: Uses v18.0 — Meta requires this version for subscribe endpoint.
     *
     * POST /{wabaId}/subscribed_apps
     */
    public MetaApiResponse subscribeWabaWebhook(String wabaId, String accessToken) {
        log.debug("Subscribing to webhook for WABA: {}", wabaId);
        // FIX 2: v18.0 required for this endpoint
        String url = config.getRegistrationVersionedBaseUrl() + "/" + wabaId + "/subscribed_apps";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("access_token", accessToken);
        // FIX 1: subscribed_fields is REQUIRED — without it Meta ignores the subscription
        body.add("subscribed_fields", "messages");
        return post(url, body, null);
    }

    public MetaApiResponse subscribeWabaToWebhook(String wabaId, String accessToken) {
        return subscribeWabaWebhook(wabaId, accessToken);
    }

    // ═══════════════════════════════════════════════════════════
    // PHASE 1.5 — Phone Registration (v18.0)
    // ═══════════════════════════════════════════════════════════

    /**
     * FIX 2: Uses v18.0 — Meta requires this version for register endpoint.
     * POST /{phoneNumberId}/register
     */
    public MetaApiResponse registerPhoneNumber(String phoneNumberId,
                                               String accessToken,
                                               String pin) {
        log.info("Registering phone number with Meta: phoneNumberId={}", phoneNumberId);
        // FIX 2: v18.0 required for this endpoint
        String url = config.getRegistrationVersionedBaseUrl() + "/" + phoneNumberId + "/register";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("messaging_product", "whatsapp");
        body.add("pin",              pin);
        return post(url, body, accessToken);
    }

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

    public MetaApiResponse verifyCode(String phoneNumberId,
                                      String accessToken,
                                      String code) {
        log.info("Verifying OTP for phone number: phoneNumberId={}", phoneNumberId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/verify_code", body, accessToken);
    }

    // ═══════════════════════════════════════════════════════════
    // PHASE 1.5b — Coexistence SMB Sync (v23.0)
    // ═══════════════════════════════════════════════════════════

    public MetaApiResponse syncSmbAppState(String phoneNumberId, String accessToken) {
        log.info("Initiating SMB app state sync: phoneNumberId={}", phoneNumberId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("messaging_product", "whatsapp");
        body.add("sync_type", "smb_app_state_sync");
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/smb_app_data",
                body, accessToken);
    }

    public MetaApiResponse syncSmbHistory(String phoneNumberId, String accessToken) {
        log.info("Initiating SMB history sync: phoneNumberId={}", phoneNumberId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("messaging_product", "whatsapp");
        body.add("sync_type", "history");
        return post(config.getVersionedBaseUrl() + "/" + phoneNumberId + "/smb_app_data",
                body, accessToken);
    }

    // ═══════════════════════════════════════════════════════════
    // PHASE 2 — System User Provisioning (v23.0)
    // ═══════════════════════════════════════════════════════════

    public MetaApiResponse createSystemUser(String businessId, String name,
                                            String role, String accessToken) {
        log.info("Creating system user: businessId={}, name={}, role={}", businessId, name, role);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("name",         name);
        body.add("role",         role);
        body.add("access_token", accessToken);
        return post(config.getVersionedBaseUrl() + "/" + businessId + "/system_users", body, null);
    }

    public MetaApiResponse assignWabaToSystemUser(String wabaId, String systemUserId,
                                                  String accessToken) {
        log.info("Assigning WABA to system user: wabaId={}, systemUserId={}", wabaId, systemUserId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("user",         systemUserId);
        body.add("tasks",        "[\"MANAGE\"]");
        body.add("access_token", accessToken);
        return post(config.getVersionedBaseUrl() + "/" + wabaId + "/assigned_users", body, null);
    }

    public MetaApiResponse generateSystemUserToken(String systemUserId, String appId,
                                                   String appSecretProof, String scope,
                                                   String accessToken) {
        log.info("Generating permanent token for system user: {}", systemUserId);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("business_app",    appId);
        body.add("appsecret_proof", appSecretProof);
        body.add("scope",           scope);
        body.add("access_token",    accessToken);
        return post(config.getVersionedBaseUrl() + "/" + systemUserId + "/access_tokens", body, null);
    }

    // ═══════════════════════════════════════════════════════════
    // TOKEN HEALTH
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // HTTP HELPERS
    // ═══════════════════════════════════════════════════════════

    private MetaApiResponse get(String url) {
        try {
            MetaApiResponse response = webClient.get()
                    .uri(url)
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

    private MetaApiResponse post(String url, MultiValueMap<String, String> formBody,
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
            String body = ex.getResponseBodyAsString();
            log.warn("Meta API error {}: {}", ex.getStatusCode(), body);

            if (ex.getStatusCode().is5xxServerError()) {
                throw new MetaApiException("Meta API server error: " + ex.getMessage(), ex);
            }

            MetaApiResponse fallback = new MetaApiResponse();
            fallback.setSuccess(false);
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