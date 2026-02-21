package com.aigreentick.services.wabaaccounts.scheduler;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.service.OnboardingOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ══════════════════════════════════════════════════════════════════
 * Token Maintenance Scheduler
 * ══════════════════════════════════════════════════════════════════
 *
 * ───────────────
 * During BSP onboarding review, Meta will ask: "How do you monitor
 * token health?" The correct answer is a scheduled process that:
 *
 *   1. Finds accounts still running on temporary USER tokens
 *      (Phase 2 system user provisioning failed or never ran)
 *      and retries provisioning automatically
 *
 *   2. Calls /debug_token on every active WABA account's token
 *      to detect revocations, near-expiry, and missing scopes
 *      before they cause message delivery failures at 3am
 *
 *   3. Resets stuck onboarding tasks (worker crashed mid-flight)
 *
 *   4. Retries failed onboarding tasks (e.g. transient Meta API errors)
 *
 * SCHEDULE
 * ─────────
 *   Token health check:      02:00 daily     (low-traffic window)
 *   Stuck task reset:        Every 10 min    (quick recovery)
 *   Failed task retry:       Every 30 min    (backoff for transient errors)
 *
 * ALERTING
 * ─────────
 * Currently logs WARN/ERROR for human review. For production add:
 *   - PagerDuty / OpsGenie alert when is_valid=false
 *   - Slack webhook for near-expiry warnings
 *   - Dead-letter queue for permanently failed tasks
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenMaintenanceScheduler {

    private final MetaOAuthAccountRepository metaOAuthAccountRepository;
    private final MetaApiClient metaApiClient;
    private final OnboardingOrchestrator orchestrator;
    private final com.aigreentick.services.wabaaccounts.service.TokenEncryptionService tokenEncryptionService;

    /** Days before expiry to start warning */
    private static final int EXPIRY_WARN_DAYS = 7;

    // ════════════════════════════════════════════════════════════
    // 1. Daily Token Health Check — 02:00 server time
    // ════════════════════════════════════════════════════════════

    /**
     * Inspect every active WABA account's token for validity and expiry.
     *
     * Runs at 02:00 daily — quiet window, before business hours.
     * On average takes ~200ms per account (one /debug_token call each).
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runTokenHealthCheck() {
        log.info("=== Token Health Check START ===");

        List<MetaOAuthAccount> activeAccounts =
                metaOAuthAccountRepository.findAll();


        if (activeAccounts.isEmpty()) {
            log.info("No active WABA accounts to check");
            return;
        }

        log.info("Checking token health for {} active accounts", activeAccounts.size());

        int healthy = 0, expiringSoon = 0, invalid = 0, errors = 0;

        for (MetaOAuthAccount account : activeAccounts) {
            try {
                TokenHealthResult result = checkTokenHealth(account);
                switch (result) {
                    case HEALTHY         -> healthy++;
                    case EXPIRING_SOON   -> expiringSoon++;
                    case INVALID         -> invalid++;
                    case CHECK_FAILED    -> errors++;
                }
            } catch (Exception ex) {
                log.error("Unexpected error checking token for account {}: {}",
                        account.getId(), ex.getMessage(), ex);
                errors++;
            }
        }

        log.info("=== Token Health Check DONE: healthy={}, expiringSoon={}, invalid={}, errors={} ===",
                healthy, expiringSoon, invalid, errors);

        if (invalid > 0) {
            log.error("ACTION REQUIRED: {} WABA accounts have invalid/revoked tokens. " +
                    "These accounts cannot send messages. Check logs above.", invalid);
        }
        if (expiringSoon > 0) {
            log.warn("WARNING: {} WABA accounts have tokens expiring within {} days. " +
                            "Phase 2 provisioning should have replaced these with permanent tokens.",
                    expiringSoon, EXPIRY_WARN_DAYS);
        }
    }

    /**
     * Inspect a single account's token.
     *
     * USER tokens (temporary, 60-day) should have been replaced by Phase 2
     * with a permanent SYSTEM_USER token. If we still see a USER token type,
     * Phase 2 provisioning is incomplete.
     */
    private TokenHealthResult checkTokenHealth(MetaOAuthAccount account) {
        String encryptedToken = account.getAccessToken();
        if (encryptedToken == null || encryptedToken.isBlank()) {
            log.error("Account {} has no access token stored — cannot send messages", account.getId());
            return TokenHealthResult.INVALID;
        }

        String token;
        try {
            token = tokenEncryptionService.decrypt(encryptedToken);
        } catch (Exception ex) {
            log.error("Account {}: token decryption failed — key mismatch or data corruption. Error: {}",
                    account.getId(), ex.getMessage());
            return TokenHealthResult.INVALID;
        }

        MetaApiResponse debugResponse = metaApiClient.debugToken(token);

        if (!Boolean.TRUE.equals(debugResponse.getSuccess())) {
            log.warn("Failed to call /debug_token for account {}: {}",
                    account.getId(), debugResponse.getErrorMessage());
            return TokenHealthResult.CHECK_FAILED;
        }

        // Parse the debug_token data block
        Map<String, Object> data = debugResponse.getData();
        if (data == null) {
            log.warn("Account {}: /debug_token returned no data", account.getId());
            return TokenHealthResult.CHECK_FAILED;
        }

        Boolean isValid  = (Boolean) data.get("is_valid");
        Object  expiresAt = data.get("expires_at");
        String  tokenType = (String) data.getOrDefault("type", "UNKNOWN");

        // ── Validity check ──────────────────────────────────────
        if (!Boolean.TRUE.equals(isValid)) {
            log.error("Org {}: token INVALID/REVOKED (oauthAccountId={}, type={}). " +
                            "Customer must reconnect Meta.",
                    account.getOrganizationId(),
                    account.getId(),
                    tokenType);
            // TODO: mark account as TOKEN_REVOKED, alert ops, suspend messaging
            return TokenHealthResult.INVALID;
        }

        // ── Token type warning ───────────────────────────────────
        if ("USER".equalsIgnoreCase(tokenType)) {
            log.warn("Account {}: still using USER token (Phase 2 provisioning incomplete). " +
                            "Token expires in ~60 days. Retrying Phase 2 provisioning now.",
                    account.getId());
            // The retry scheduler will pick this up via retryFailedTasks()
        }

        // ── Expiry check ─────────────────────────────────────────
        if (expiresAt != null) {
            long expiresAtUnix = Long.parseLong(expiresAt.toString());

            // 0 = permanent token (system user token)
            if (expiresAtUnix > 0) {
                long nowUnix           = Instant.now().getEpochSecond();
                long secondsUntilExpiry = expiresAtUnix - nowUnix;
                long daysUntilExpiry   = secondsUntilExpiry / 86400;

                if (daysUntilExpiry <= 0) {
                    log.error("Account {}: token EXPIRED {}d ago! Messages are failing NOW.",
                            account.getId(), Math.abs(daysUntilExpiry));
                    return TokenHealthResult.INVALID;
                }

                if (daysUntilExpiry <= EXPIRY_WARN_DAYS) {
                    log.warn("Account {}: token expires in {} days. " +
                                    "Permanent system user token should have replaced this.",
                            account.getId(), daysUntilExpiry);
                    return TokenHealthResult.EXPIRING_SOON;
                }
            }
        }

        log.debug("Org {}: token healthy (oauthAccountId={}, type={})",
                account.getOrganizationId(),
                account.getId(),
                tokenType);
        return TokenHealthResult.HEALTHY;
    }

    // ════════════════════════════════════════════════════════════
    // 2. Stuck Task Recovery — every 10 minutes
    // ════════════════════════════════════════════════════════════

    /**
     * Reset PROCESSING tasks that have been running for > 10 minutes.
     *
     * This guards against worker crashes (OOM kill, JVM crash, pod restart).
     * Without this, stuck tasks block the PROCESSING → COMPLETED transition
     * forever and the frontend spins indefinitely.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000) // 10 minutes
    public void resetStuckTasks() {
        int reset = orchestrator.resetStuckTasks();
        if (reset > 0) {
            log.warn("Reset {} stuck onboarding tasks back to PENDING", reset);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 3. Failed Task Retry — every 30 minutes
    // ════════════════════════════════════════════════════════════

    /**
     * Re-queue FAILED onboarding tasks that have retried fewer than 3 times.
     *
     * Most failures are transient Meta API errors (503, rate limit, timeouts).
     * Retrying after 30 minutes is usually sufficient for them to resolve.
     *
     * After 3 failures, tasks stay FAILED and require manual investigation
     * (check errorMessage column, check Meta Business Manager directly).
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000) // 30 minutes
    public void retryFailedOnboardingTasks() {
        int retried = orchestrator.retryFailedTasks();
        if (retried > 0) {
            log.info("Queued {} failed onboarding tasks for retry", retried);
        }
    }

    // ════════════════════════════════════════════════════════════
    // Internal types
    // ════════════════════════════════════════════════════════════

    private enum TokenHealthResult {
        HEALTHY,        // Valid, not expiring soon
        EXPIRING_SOON,  // Valid, but expires within EXPIRY_WARN_DAYS
        INVALID,        // Revoked, expired, or explicitly invalid
        CHECK_FAILED    // Could not reach /debug_token
    }
}