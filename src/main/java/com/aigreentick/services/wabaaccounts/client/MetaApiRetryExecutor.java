package com.aigreentick.services.wabaaccounts.client;

import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.exception.MetaApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * ══════════════════════════════════════════════════════════════════
 * Meta API Retry Executor — Exponential Backoff
 * ══════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS (Senior Gap 5)
 * ────────────────────────────────
 * Meta API calls were single-shot:
 *   - One timeout → onboarding failure
 *   - One 5xx → onboarding failure
 *   - One rate limit → onboarding failure
 *
 * Meta's APIs are reliable but NOT 100%.
 * At BSP scale (thousands of signups/day), transient failures WILL happen.
 *
 * This wrapper adds:
 *   1. Exponential backoff retry (transient errors)
 *   2. Transient vs permanent error classification
 *   3. Rate limit detection + longer wait
 *   4. Max attempts guard (no infinite loops)
 *
 * RETRY STRATEGY
 * ────────────────
 * Attempt 1  → immediate
 * Attempt 2  → wait 1s (base delay)
 * Attempt 3  → wait 2s (1s * 2^1)
 * Attempt 4  → wait 4s (1s * 2^2)  [max 3 retries = 4 total attempts]
 *
 * For rate limits (HTTP 429):
 * Attempt 2+ → wait 5s (rate limit has a higher base delay)
 *
 * WHAT IS RETRYABLE
 * ─────────────────
 * ✅ Retryable (transient):
 *   - Meta 5xx errors (server-side issues)
 *   - Network timeouts / connection resets
 *   - HTTP 429 (rate limited — wait longer)
 *
 * ❌ Not retryable (permanent — would just fail again):
 *   - HTTP 400 (bad request, wrong params)
 *   - HTTP 190 (OAuthException — invalid/expired token)
 *   - HTTP 200 with error.type = "OAuthException"
 *   - HTTP 403 (permission denied)
 *
 * USAGE
 * ──────
 * Inject into service, then wrap calls:
 *
 *   metaRetry.execute("createSystemUser",
 *       () -> metaApiClient.createSystemUser(bizId, name, role, token));
 *
 * Or for specific step:
 *   MetaApiResponse response = metaRetry.executeWithContext(
 *       "assignWaba", wabaId,
 *       () -> metaApiClient.assignWabaToSystemUser(wabaId, systemUserId, token));
 */
@Component
@Slf4j
public class MetaApiRetryExecutor {

    private static final int MAX_ATTEMPTS        = 4;        // 1 initial + 3 retries
    private static final long BASE_DELAY_MS      = 1_000L;   // 1 second base
    private static final long RATE_LIMIT_DELAY_MS = 5_000L;  // 5 seconds for 429

    // Meta error codes that are permanent (no point retrying)
    private static final java.util.Set<Integer> PERMANENT_ERROR_CODES = java.util.Set.of(
            190,  // OAuthException: invalid/expired token
            200,  // Permission error
            10,   // Permission denied
            100,  // Invalid parameter
            102   // Session key invalid / must be re-authenticated
    );

    /**
     * Execute a Meta API call with retry.
     *
     * @param operationName  Human-readable name for logging (e.g. "createSystemUser")
     * @param apiCall        The API call to execute (lambda)
     * @return               The successful MetaApiResponse
     * @throws MetaApiException if all retries exhausted or a permanent error occurs
     */
    public MetaApiResponse execute(String operationName, Supplier<MetaApiResponse> apiCall) {
        return executeInternal(operationName, null, apiCall);
    }

    /**
     * Execute with a resource ID for better log messages.
     *
     * @param operationName  Human-readable name (e.g. "assignWaba")
     * @param resourceId     The WABA ID / business ID / system user ID being operated on
     * @param apiCall        The API call to execute
     */
    public MetaApiResponse executeWithContext(String operationName,
                                              String resourceId,
                                              Supplier<MetaApiResponse> apiCall) {
        return executeInternal(operationName, resourceId, apiCall);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE
    // ════════════════════════════════════════════════════════════

    private MetaApiResponse executeInternal(String opName,
                                            String resourceId,
                                            Supplier<MetaApiResponse> apiCall) {
        String context = resourceId != null ? opName + "[" + resourceId + "]" : opName;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                MetaApiResponse response = apiCall.get();

                // Check if Meta returned an application-level error (even on HTTP 200)
                if (!response.isOk()) {
                    int errorCode = extractErrorCode(response);

                    if (isPermanentError(errorCode, response)) {
                        // Don't retry — this will fail on every attempt
                        log.error("{} failed permanently (attempt {}/{}): code={}, msg={}",
                                context, attempt, MAX_ATTEMPTS,
                                errorCode, response.getErrorMessage());
                        throw new MetaApiException(
                                context + " failed: " + response.getErrorMessage());
                    }

                    if (attempt == MAX_ATTEMPTS) {
                        throw new MetaApiException(
                                context + " failed after " + MAX_ATTEMPTS + " attempts: " +
                                        response.getErrorMessage());
                    }

                    log.warn("{} got retryable error (attempt {}/{}): code={}, msg={}. Retrying...",
                            context, attempt, MAX_ATTEMPTS, errorCode, response.getErrorMessage());

                    boolean isRateLimit = (errorCode == 429 || errorCode == 4);
                    sleep(backoffMs(attempt, isRateLimit), context, attempt);
                    continue;
                }

                if (attempt > 1) {
                    log.info("{} succeeded after {} attempts", context, attempt);
                }
                return response;

            } catch (MetaApiException ex) {
                // Permanent errors propagate immediately
                throw ex;
            } catch (Exception ex) {
                lastException = ex;

                if (attempt == MAX_ATTEMPTS) {
                    log.error("{} exhausted all {} attempts. Last error: {}",
                            context, MAX_ATTEMPTS, ex.getMessage());
                    throw new MetaApiException(
                            context + " failed after " + MAX_ATTEMPTS + " attempts: " +
                                    ex.getMessage(), ex);
                }

                log.warn("{} threw exception (attempt {}/{}): {}. Retrying...",
                        context, attempt, MAX_ATTEMPTS, ex.getMessage());
                sleep(backoffMs(attempt, false), context, attempt);
            }
        }

        // Should never reach here — loop always throws or returns
        throw new MetaApiException(context + " failed unexpectedly", lastException);
    }

    /**
     * Exponential backoff: BASE * 2^(attempt-1)
     * With higher base for rate limits.
     */
    private long backoffMs(int attempt, boolean isRateLimit) {
        long base = isRateLimit ? RATE_LIMIT_DELAY_MS : BASE_DELAY_MS;
        long delay = base * (1L << (attempt - 1)); // 1s, 2s, 4s, 8s...
        return Math.min(delay, 30_000L);            // Cap at 30 seconds
    }

    private void sleep(long ms, String context, int attempt) {
        try {
            log.debug("{} waiting {}ms before attempt {}", context, ms, attempt + 1);
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MetaApiException(context + " interrupted during retry backoff");
        }
    }

    private int extractErrorCode(MetaApiResponse response) {
        if (response.getError() == null) return -1;
        Object code = response.getError().get("code");
        if (code == null) return -1;
        try {
            return Integer.parseInt(String.valueOf(code));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private boolean isPermanentError(int code, MetaApiResponse response) {
        if (PERMANENT_ERROR_CODES.contains(code)) return true;
        // OAuthException is always permanent
        if (response.getError() != null) {
            Object type = response.getError().get("type");
            if ("OAuthException".equals(type)) return true;
        }
        return false;
    }
}