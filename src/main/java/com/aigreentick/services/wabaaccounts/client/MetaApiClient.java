package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * HTTP client for Meta (Facebook) Graph API
 * All WhatsApp Business API calls go through this class
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MetaApiClient {

    @Qualifier("metaWebClient")
    private final WebClient webClient;
    private final MetaApiConfig metaApiConfig;

    // ========================
    // OAUTH
    // ========================

    /**
     * Exchange OAuth authorization code for access token
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    @Retry(name = "metaApi")
    public MetaApiResponse exchangeCodeForToken(String code) {
        log.info("Exchanging OAuth code for access token");

        try {
            Map response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/oauth/access_token")
                            .queryParam("code", code)
                            .queryParam("client_id", metaApiConfig.getAppId())
                            .queryParam("client_secret", metaApiConfig.getAppSecret())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(Map.class)
                                    .map(body -> new MetaApiException("OAuth code exchange failed: " + body, 400))
                    )
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Meta server error: " + body, 500))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.info("Successfully exchanged OAuth code for token");
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Meta API error during token exchange: status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new MetaApiException("Token exchange failed: " + ex.getMessage(), ex);
        }
    }

    // ========================
    // WABA OPERATIONS
    // ========================

    /**
     * Get WABA account details from Meta
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    @Retry(name = "metaApi")
    public MetaApiResponse getWabaDetails(String wabaId, String accessToken) {
        log.debug("Fetching WABA details for: {}", wabaId);

        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{wabaId}")
                            .queryParam("fields", "id,name,currency,timezone_id,message_template_namespace")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("WABA fetch failed: " + body))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.debug("WABA details fetched for: {}", wabaId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to fetch WABA details: wabaId={}, status={}", wabaId, ex.getStatusCode());
            if (ex.getStatusCode().value() == 401) {
                throw MetaApiException.unauthorized();
            }
            throw new MetaApiException("Failed to fetch WABA details", ex);
        }
    }

    // ========================
    // PHONE NUMBER OPERATIONS
    // ========================

    /**
     * Get all phone numbers for a WABA
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    @Retry(name = "metaApi")
    public MetaApiResponse getPhoneNumbers(String wabaId, String accessToken) {
        log.debug("Fetching phone numbers for WABA: {}", wabaId);

        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{wabaId}/phone_numbers")
                            .queryParam("fields", "id,verified_name,display_phone_number,quality_rating,status,messaging_limit_tier")
                            .build(wabaId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Phone number fetch failed: " + body))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.debug("Phone numbers fetched for WABA: {}", wabaId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to fetch phone numbers: wabaId={}, status={}", wabaId, ex.getStatusCode());
            throw new MetaApiException("Failed to fetch phone numbers", ex);
        }
    }

    /**
     * Request OTP verification code
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    public MetaApiResponse requestVerificationCode(String phoneNumberId, String accessToken,
                                                   String method, String locale) {
        log.info("Requesting verification code for phone: {}, method: {}", phoneNumberId, method);

        try {
            Map response = webClient.post()
                    .uri("/{phoneNumberId}/request_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of(
                            "code_method", method,
                            "language", locale
                    ))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Verification code request failed: " + body))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.info("Verification code requested for phone: {}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to request verification code: phoneNumberId={}", phoneNumberId);
            throw new MetaApiException("Failed to request verification code", ex);
        }
    }

    /**
     * Verify phone number with received OTP
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    public MetaApiResponse verifyCode(String phoneNumberId, String accessToken, String code) {
        log.info("Verifying code for phone: {}", phoneNumberId);

        try {
            Map response = webClient.post()
                    .uri("/{phoneNumberId}/verify_code", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of("code", code))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Code verification failed: " + body))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.info("Phone number verified: {}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to verify code for phone: {}", phoneNumberId);
            throw new MetaApiException("Failed to verify phone number", ex);
        }
    }

    /**
     * Register phone number (enable messaging)
     */
    @CircuitBreaker(name = "metaApi", fallbackMethod = "handleMetaApiUnavailable")
    public MetaApiResponse registerPhoneNumber(String phoneNumberId, String accessToken, String pin) {
        log.info("Registering phone number: {}", phoneNumberId);

        try {
            Map response = webClient.post()
                    .uri("/{phoneNumberId}/register", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .bodyValue(Map.of(
                            "messaging_product", "whatsapp",
                            "pin", pin
                    ))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new MetaApiException("Phone registration failed: " + body))
                    )
                    .bodyToMono(Map.class)
                    .block();

            log.info("Phone number registered: {}", phoneNumberId);
            return MetaApiResponse.success(response);

        } catch (WebClientResponseException ex) {
            log.error("Failed to register phone: {}", phoneNumberId);
            throw new MetaApiException("Failed to register phone number", ex);
        }
    }

    // ========================
    // CIRCUIT BREAKER FALLBACK
    // ========================

    /**
     * Fallback when Meta API is unavailable
     */
    private MetaApiResponse handleMetaApiUnavailable(Exception ex) {
        log.error("Meta API circuit breaker triggered: {}", ex.getMessage());
        throw MetaApiException.serviceUnavailable();
    }
}