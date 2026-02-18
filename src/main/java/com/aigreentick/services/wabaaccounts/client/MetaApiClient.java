package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
//import org.springframework.retry.annotation.Backoff;
//import org.springframework.retry.annotation.Recover;
//import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * HTTP client for Meta (Facebook) Graph API
 * Direct Cloud API integration — no BSP middleware
 * All Meta WhatsApp API calls go through this class
 *
 * Retry Strategy:
 * - 3 attempts with exponential backoff (1s → 2s → 4s)
 * - Only retries on transient errors (5xx, IO errors)
 * - Does NOT retry on 4xx (client errors — don't retry bad requests)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MetaApiClient {

    @Qualifier("metaWebClient")
    private final WebClient webClient;
    private final MetaApiConfig metaApiConfig;

    // ========================
    // TYPE REFERENCE CONSTANTS
    // Avoids raw Map warnings
    // ========================
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // ========================
    // OAUTH
    // ========================

    /**
     * Exchange OAuth authorization code for access token
     * Called once during embedded signup flow
     */
//    @Retryable(
//            retryFor = {MetaApiException.class},
//            noRetryFor = {MetaApiException.ClientException.class},
//            maxAttempts = 3,
//            backoff = @Backoff(delay = 1000, multiplier = 2.0)
//    )
    public MetaApiResponse exchangeCodeForToken(String code) {
        log.info("Exchanging OAuth code for access token");

        try {
            Map<String, Object> response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/oauth/access_token")
                            .queryParam("code", code)
                            .queryParam("client_id", metaApiConfig.getAppId())
                            .queryParam("client_secret", metaApiConfig.getAppSecret())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "OAuth code exchange failed: " + body, 400))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Successfully exchanged OAuth code for access token");
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Meta API error during token exchange: status={}", ex.getStatusCode());
            throw handleWebClientException(ex);
        }
    }

//    @Recover
    public MetaApiResponse exchangeCodeForTokenFallback(MetaApiException ex, String code) {
        log.error("All retry attempts exhausted for token exchange. Error: {}", ex.getMessage());
        throw MetaApiException.serviceUnavailable();
    }

    // ========================
    // WABA OPERATIONS
    // ========================

    /**
     * Get WABA account details from Meta
     */
//    @Retryable(
//            retryFor = {MetaApiException.class},
//            noRetryFor = {MetaApiException.ClientException.class},
//            maxAttempts = 3,
//            backoff = @Backoff(delay = 1000, multiplier = 2.0)
//    )
    public MetaApiResponse getWabaDetails(String wabaId, String accessToken) {
        log.debug("Fetching WABA details for wabaId: {}", wabaId);

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{wabaId}")
                            .queryParam("fields", "id,name,currency,timezone_id,message_template_namespace")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "WABA fetch failed: " + body,
                                            clientResponse.statusCode().value()))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.debug("WABA details fetched for wabaId: {}", wabaId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to fetch WABA details: wabaId={}, status={}", wabaId, ex.getStatusCode());
            throw handleWebClientException(ex);
        }
    }

//    @Recover
    public MetaApiResponse getWabaDetailsFallback(MetaApiException ex, String wabaId, String accessToken) {
        log.error("All retry attempts exhausted for getWabaDetails. wabaId={}", wabaId);
        throw MetaApiException.serviceUnavailable();
    }

    // ========================
    // PHONE NUMBER OPERATIONS
    // ========================

    /**
     * Get all phone numbers registered under a WABA
     */
//    @Retryable(
//            retryFor = {MetaApiException.class},
//            noRetryFor = {MetaApiException.ClientException.class},
//            maxAttempts = 3,
//            backoff = @Backoff(delay = 1000, multiplier = 2.0)
//    )
    public MetaApiResponse getPhoneNumbers(String wabaId, String accessToken) {
        log.debug("Fetching phone numbers for wabaId: {}", wabaId);

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{wabaId}/phone_numbers")
                            .queryParam("fields",
                                    "id,verified_name,display_phone_number,quality_rating,status,messaging_limit_tier")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Phone number fetch failed: " + body,
                                            clientResponse.statusCode().value()))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.debug("Phone numbers fetched for wabaId: {}", wabaId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to fetch phone numbers: wabaId={}, status={}", wabaId, ex.getStatusCode());
            throw handleWebClientException(ex);
        }
    }

//    @Recover
    public MetaApiResponse getPhoneNumbersFallback(MetaApiException ex, String wabaId, String accessToken) {
        log.error("All retry attempts exhausted for getPhoneNumbers. wabaId={}", wabaId);
        throw MetaApiException.serviceUnavailable();
    }

    /**
     * Request OTP verification code for phone number (SMS or Voice)
     * Not retried — user should manually retry
     */
    public MetaApiResponse requestVerificationCode(String phoneNumberId, String accessToken,
                                                   String method, String locale) {
        log.info("Requesting verification code: phoneNumberId={}, method={}", phoneNumberId, method);

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/request_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of(
                            "code_method", method,
                            "language", locale
                    ))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Verification code request failed: " + body,
                                            clientResponse.statusCode().value()))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Verification code sent for phoneNumberId: {}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to request verification code: phoneNumberId={}", phoneNumberId);
            throw handleWebClientException(ex);
        }
    }

    /**
     * Verify phone number with OTP received via SMS or Voice
     * Not retried — wrong code should not be retried
     */
    public MetaApiResponse verifyCode(String phoneNumberId, String accessToken, String code) {
        log.info("Verifying OTP for phoneNumberId: {}", phoneNumberId);

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/verify_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of("code", code))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Code verification failed: " + body,
                                            clientResponse.statusCode().value()))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Phone number verified: phoneNumberId={}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to verify code: phoneNumberId={}", phoneNumberId);
            throw handleWebClientException(ex);
        }
    }

    /**
     * Register phone number with Meta to enable messaging
     */
//    @Retryable(
//            retryFor = {MetaApiException.class},
//            noRetryFor = {MetaApiException.ClientException.class},
//            maxAttempts = 3,
//            backoff = @Backoff(delay = 1000, multiplier = 2.0)
//    )
    public MetaApiResponse registerPhoneNumber(String phoneNumberId, String accessToken, String pin) {
        log.info("Registering phone number: phoneNumberId={}", phoneNumberId);

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{phoneNumberId}/register", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of(
                            "messaging_product", "whatsapp",
                            "pin", pin
                    ))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException.ClientException(
                                            "Phone registration failed: " + body,
                                            clientResponse.statusCode().value()))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body))
                    )
                    .bodyToMono(MAP_TYPE)
                    .block();

            log.info("Phone number registered: phoneNumberId={}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to register phone number: phoneNumberId={}", phoneNumberId);
            throw handleWebClientException(ex);
        }
    }

//    @Recover
    public MetaApiResponse registerPhoneNumberFallback(MetaApiException ex,
                                                       String phoneNumberId,
                                                       String accessToken,
                                                       String pin) {
        log.error("All retry attempts exhausted for registerPhoneNumber. phoneNumberId={}", phoneNumberId);
        throw MetaApiException.serviceUnavailable();
    }

    // ========================
    // PRIVATE HELPERS
    // ========================

    /**
     * Convert WebClient exceptions to our domain exceptions
     */
    private RuntimeException handleWebClientException(WebClientResponseException ex) {
        int status = ex.getStatusCode().value();

        if (status == 401) {
            return MetaApiException.unauthorized();
        }
        if (status >= 400 && status < 500) {
            return new MetaApiException.ClientException("Meta API client error: " + ex.getMessage(), status);
        }
        return new MetaApiException("Meta API server error: " + ex.getMessage());
    }
}