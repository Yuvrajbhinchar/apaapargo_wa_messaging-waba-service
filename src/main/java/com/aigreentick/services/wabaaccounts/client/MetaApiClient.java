package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * HTTP client for Meta (Facebook) Graph API — Direct Cloud API integration.
 *
 * Resilience strategy (outermost → innermost):
 *   RateLimiter → Retry → CircuitBreaker → actual HTTP call
 *
 *   1. RateLimiter  — enforces 80 req/min per Meta limits (configured in YAML)
 *   2. Retry        — 3 attempts, exponential backoff 1s→2s→4s, 5xx only
 *   3. CircuitBreaker — opens after 50% failure rate; stays open 30s
 *
 * Fallback: all retryable methods throw MetaApiException.serviceUnavailable()
 * which returns HTTP 503 to the caller via GlobalExceptionHandler.
 *
 * 4xx (ClientException) are NEVER retried — bad requests shouldn't be replayed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MetaApiClient {

    @Qualifier("metaWebClient")
    private final WebClient webClient;
    private final MetaApiConfig metaApiConfig;

    private static final String CB_NAME = "metaApi";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // ======================================================
    // OAUTH — Token Exchange
    // ======================================================

    /**
     * Exchange short-lived OAuth code for an access token.
     * Called once per embedded signup flow.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse exchangeCodeForToken(String code) {
        log.info("Exchanging OAuth code for access token");
        try {
            Map<String, Object> response = webClient.post()
                    .uri(uri -> uri
                            .path("/oauth/access_token")
                            .queryParam("code", code)
                            .queryParam("client_id", metaApiConfig.getAppId())
                            .queryParam("client_secret", metaApiConfig.getAppSecret())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "OAuth code exchange failed (4xx): " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error during token exchange: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("OAuth token exchange successful");
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    /**
     * Exchange user access token for a long-lived token (60 days).
     * Meta best practice for production SaaS integrations.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse extendAccessToken(String shortLivedToken) {
        log.info("Extending access token to long-lived (60-day)");
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri -> uri
                            .path("/oauth/access_token")
                            .queryParam("grant_type", "fb_exchange_token")
                            .queryParam("client_id", metaApiConfig.getAppId())
                            .queryParam("client_secret", metaApiConfig.getAppSecret())
                            .queryParam("fb_exchange_token", shortLivedToken)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Token extension failed (4xx): " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error extending token: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Token extended to long-lived successfully");
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    /**
     * Get all WABA accounts the user has access to (post-signup).
     * Used to auto-discover WABA after embedded signup.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse getBusinessAccounts(String accessToken) {
        log.debug("Fetching business accounts for user token");
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri -> uri
                            .path("/me/businesses")
                            .queryParam("fields",
                                    "id,name,owned_whatsapp_business_accounts{id,name,currency,timezone_id}")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Business accounts fetch failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    // ======================================================
    // WABA OPERATIONS
    // ======================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse getWabaDetails(String wabaId, String accessToken) {
        log.debug("Fetching WABA details: wabaId={}", wabaId);
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri -> uri
                            .path("/{wabaId}")
                            .queryParam("fields",
                                    "id,name,currency,timezone_id,message_template_namespace,business_verification_status")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "WABA fetch failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.debug("WABA details fetched: wabaId={}", wabaId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    /**
     * Subscribe WABA to webhook notifications.
     * Must be called once after WABA creation.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse subscribeWabaToWebhook(String wabaId, String accessToken) {
        log.info("Subscribing WABA to webhook: wabaId={}", wabaId);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{wabaId}/subscribed_apps", wabaId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Webhook subscription failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("WABA webhook subscription successful: wabaId={}", wabaId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    // ======================================================
    // PHONE NUMBER OPERATIONS
    // ======================================================

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse getPhoneNumbers(String wabaId, String accessToken) {
        log.debug("Fetching phone numbers: wabaId={}", wabaId);
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri -> uri
                            .path("/{wabaId}/phone_numbers")
                            .queryParam("fields",
                                    "id,verified_name,display_phone_number,quality_rating,status,messaging_limit_tier,code_verification_status")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Phone numbers fetch failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.debug("Phone numbers fetched: wabaId={}", wabaId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    /**
     * Request OTP via SMS or Voice call.
     * NOT retried — user should manually retry.
     */
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse requestVerificationCode(String phoneNumberId, String accessToken,
                                                   String method, String locale) {
        log.info("Requesting verification: phoneNumberId={}, method={}", phoneNumberId, method);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/request_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of("code_method", method, "language", locale))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Verification code request failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Verification code sent: phoneNumberId={}", phoneNumberId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    /**
     * Verify OTP code.
     * NOT retried — wrong code should not be replayed.
     */
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse verifyCode(String phoneNumberId, String accessToken, String code) {
        log.info("Verifying OTP: phoneNumberId={}", phoneNumberId);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/verify_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of("code", code))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Code verification failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Phone verified: phoneNumberId={}", phoneNumberId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackMetaApiResponse")
    @Retry(name = CB_NAME)
    @RateLimiter(name = CB_NAME)
    public MetaApiResponse registerPhoneNumber(String phoneNumberId, String accessToken, String pin) {
        log.info("Registering phone: phoneNumberId={}", phoneNumberId);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/register", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of("messaging_product", "whatsapp", "pin", pin))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Phone registration failed: " + body,
                                            res.statusCode().value())))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body)))
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Phone registered: phoneNumberId={}", phoneNumberId);
            return MetaApiResponse.success(response);
        } catch (WebClientResponseException ex) {
            throw mapWebClientException(ex);
        }
    }

    // ======================================================
    // UNIVERSAL FALLBACKS
    // ======================================================

    /**
     * Single fallback method covering ALL @CircuitBreaker methods above.
     * Triggered when: circuit is OPEN, or all retry attempts exhausted.
     *
     * Signature rule: must match the original method signature + an Exception param.
     * We overload for the different method signatures.
     */

    // Token/business methods (1 String param)
    private MetaApiResponse fallbackMetaApiResponse(String param, Throwable ex) {
        return handleFallback(ex);
    }

    // Methods with 2 String params (wabaId + accessToken)
    private MetaApiResponse fallbackMetaApiResponse(String param1, String param2, Throwable ex) {
        return handleFallback(ex);
    }

    // Methods with 3 String params (phoneNumberId + accessToken + pin/code)
    private MetaApiResponse fallbackMetaApiResponse(String p1, String p2, String p3, Throwable ex) {
        return handleFallback(ex);
    }

    // Methods with 4 String params (requestVerificationCode)
    private MetaApiResponse fallbackMetaApiResponse(String p1, String p2, String p3, String p4, Throwable ex) {
        return handleFallback(ex);
    }

    private MetaApiResponse handleFallback(Throwable ex) {
        if (ex instanceof CallNotPermittedException) {
            log.error("Circuit OPEN — Meta API is unreachable. Calls blocked to protect the system.");
        } else {
            log.error("All retry attempts exhausted for Meta API call: {}", ex.getMessage());
        }
        throw MetaApiException.serviceUnavailable();
    }

    // ======================================================
    // PRIVATE HELPERS
    // ======================================================

    private RuntimeException mapWebClientException(WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401) return MetaApiException.unauthorized();
        if (status >= 400 && status < 500) {
            return new MetaApiException.ClientException(
                    "Meta API client error (" + status + "): " + ex.getMessage(), status);
        }
        return new MetaApiException("Meta API server error (" + status + "): " + ex.getMessage());
    }
}