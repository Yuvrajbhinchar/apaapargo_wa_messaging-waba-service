package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaHealthResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaHealthResponse.*;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaPhoneNumber;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaPhoneNumberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HealthCheckService — runs all 4 Meta API health checks for a WABA integration.
 *
 * Checks performed:
 *   1. Token debug      → is_valid, expires_at, scopes
 *   2. WABA status      → account_review_status
 *   3. Phone status     → quality_rating, status, verified_name
 *   4. Business profile → profile exists (presence = permission OK)
 *
 * Design principles:
 *   - Each check is independent — one failure does NOT abort others.
 *   - Token check drives overall_status; all other checks contribute to DEGRADED.
 *   - All Meta calls are best-effort (try/catch per check).
 *   - No DB writes — read-only diagnostic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthCheckService {

    private final MetaOAuthAccountRepository oauthAccountRepository;
    private final WabaAccountRepository wabaAccountRepository;
    private final WabaPhoneNumberRepository phoneNumberRepository;
    private final MetaApiClient metaApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    private static final List<String> REQUIRED_SCOPES = List.of(
            "whatsapp_business_management",
            "whatsapp_business_messaging",
            "business_management"
    );
    private static final int EXPIRY_WARN_DAYS = 7;

    // ════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Run the full health check for an organization.
     *
     * Reads the org's OAuth account, all its WABAs, and all phone numbers,
     * then checks each one against Meta's APIs.
     *
     * @param organizationId  org to check
     * @return comprehensive health report — never throws
     */
    @Transactional(readOnly = true)
    public WabaHealthResponse checkHealth(Long organizationId) {
        log.info("Health check started: orgId={}", organizationId);

        LocalDateTime now = LocalDateTime.now();

        // ── Load from DB ───────────────────────────────────────────────────
        MetaOAuthAccount oauthAccount = oauthAccountRepository
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "No OAuth account found for organization " + organizationId +
                                ". Complete embedded signup first."));

        List<WabaAccount> wabas = wabaAccountRepository.findByOrganizationId(organizationId);

        String accessToken;
        try {
            accessToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
        } catch (Exception ex) {
            log.error("Health check: token decryption failed for orgId={}: {}", organizationId, ex.getMessage());
            return WabaHealthResponse.builder()
                    .organizationId(organizationId)
                    .overallStatus(WabaHealthResponse.STATUS_UNHEALTHY)
                    .summary("Token decryption failed — key mismatch or data corruption. Re-run embedded signup.")
                    .checkedAt(now)
                    .tokenHealth(TokenHealth.builder()
                            .status("INVALID")
                            .detail("Token decryption failed: " + ex.getMessage())
                            .build())
                    .build();
        }

        // ── Check 1: Token ─────────────────────────────────────────────────
        TokenHealth tokenHealth = checkTokenHealth(oauthAccount, accessToken);

        // ── Collect all phone number IDs for this org ──────────────────────
        List<String> allPhoneIds = new ArrayList<>();
        for (WabaAccount waba : wabas) {
            phoneNumberRepository.findByWabaAccountId(waba.getId())
                    .stream()
                    .map(WabaPhoneNumber::getPhoneNumberId)
                    .forEach(allPhoneIds::add);
        }

        // ── Check 2: WABAs ─────────────────────────────────────────────────
        List<WabaCheck> wabaChecks = wabas.stream()
                .map(waba -> checkWaba(waba, accessToken))
                .collect(Collectors.toList());

        // ── Check 3 + 4: Phone numbers ─────────────────────────────────────
        List<PhoneCheck> phoneChecks = new ArrayList<>();
        for (WabaAccount waba : wabas) {
            List<WabaPhoneNumber> phones = phoneNumberRepository.findByWabaAccountId(waba.getId());
            for (WabaPhoneNumber phone : phones) {
                phoneChecks.add(checkPhone(phone, accessToken));
            }
        }

        // ── Derive overall status ──────────────────────────────────────────
        String overallStatus = deriveOverallStatus(tokenHealth, wabaChecks, phoneChecks);
        String summary = buildSummary(overallStatus, tokenHealth, wabaChecks, phoneChecks);

        log.info("Health check complete: orgId={}, overall={}", organizationId, overallStatus);

        return WabaHealthResponse.builder()
                .organizationId(organizationId)
                .overallStatus(overallStatus)
                .summary(summary)
                .checkedAt(now)
                .tokenHealth(tokenHealth)
                .wabaChecks(wabaChecks)
                .phoneChecks(phoneChecks)
                .build();
    }

    // ════════════════════════════════════════════════════════════
    // CHECK 1: TOKEN
    // ════════════════════════════════════════════════════════════

    private TokenHealth checkTokenHealth(MetaOAuthAccount oauthAccount, String accessToken) {
        boolean phase2Complete = oauthAccount.hasPermanentToken();
        String tokenTypeLabel = phase2Complete ? "SYSTEM_USER (permanent)" : "USER_TOKEN (60-day)";

        try {
            MetaApiResponse debug = metaApiClient.debugToken(accessToken);

            // debug_token wraps data inside { "data": { ... } }
            Map<String, Object> data = debug.getDataAsMap();
            if (data == null) {
                // Sometimes token check itself fails (bad app token, etc.)
                log.warn("Health check: /debug_token returned no data block");
                return TokenHealth.builder()
                        .tokenType(tokenTypeLabel)
                        .phase2Complete(phase2Complete)
                        .status("CHECK_FAILED")
                        .detail("Meta /debug_token returned no data. App access token may be invalid.")
                        .build();
            }

            Boolean isValid = (Boolean) data.getOrDefault("is_valid", false);
            Object expiresAtObj = data.get("expires_at");
            List<String> scopes = extractScopes(data);

            // ── Expired / invalid ────────────────────────────────────────
            if (!Boolean.TRUE.equals(isValid)) {
                return TokenHealth.builder()
                        .isValid(false)
                        .tokenType(tokenTypeLabel)
                        .phase2Complete(phase2Complete)
                        .grantedScopes(scopes)
                        .status("INVALID")
                        .detail("Token is invalid or revoked. Customer must re-run embedded signup.")
                        .build();
            }

            // ── Expiry ───────────────────────────────────────────────────
            Long daysUntilExpiry = null;
            String tokenStatus = "OK";
            String detail = "Token is valid.";

            if (expiresAtObj != null) {
                long expiresAtUnix = Long.parseLong(expiresAtObj.toString());
                if (expiresAtUnix > 0) {
                    long nowUnix = Instant.now().getEpochSecond();
                    long secondsLeft = expiresAtUnix - nowUnix;
                    daysUntilExpiry = secondsLeft / 86400;

                    if (daysUntilExpiry <= 0) {
                        tokenStatus = "EXPIRED";
                        detail = "Token expired " + Math.abs(daysUntilExpiry) + " day(s) ago. Messages are failing NOW.";
                    } else if (daysUntilExpiry <= EXPIRY_WARN_DAYS) {
                        tokenStatus = "EXPIRING_SOON";
                        detail = "Token expires in " + daysUntilExpiry + " day(s). Run POST /api/v1/system-users/provision/{orgId} to get a permanent token.";
                    } else {
                        detail = "Token valid for " + daysUntilExpiry + " more day(s).";
                    }
                }
                // expiresAtUnix == 0 means permanent (system user) token
            }

            // ── Scope check ──────────────────────────────────────────────
            List<String> missing = REQUIRED_SCOPES.stream()
                    .filter(s -> !scopes.contains(s))
                    .collect(Collectors.toList());

            if (!missing.isEmpty() && "OK".equals(tokenStatus)) {
                tokenStatus = "EXPIRING_SOON"; // reuse DEGRADED-equivalent
                detail = "Token valid but missing scopes: " + missing + ". Re-authorize via embedded signup.";
            }

            return TokenHealth.builder()
                    .isValid(true)
                    .tokenType(tokenTypeLabel)
                    .daysUntilExpiry(daysUntilExpiry)
                    .phase2Complete(phase2Complete)
                    .grantedScopes(scopes)
                    .missingScopes(missing.isEmpty() ? null : missing)
                    .status(tokenStatus)
                    .detail(detail)
                    .build();

        } catch (Exception ex) {
            log.warn("Health check: token check threw exception: {}", ex.getMessage());
            return TokenHealth.builder()
                    .tokenType(tokenTypeLabel)
                    .phase2Complete(phase2Complete)
                    .status("CHECK_FAILED")
                    .detail("Could not reach Meta /debug_token: " + ex.getMessage())
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════
    // CHECK 2: WABA REVIEW STATUS
    // ════════════════════════════════════════════════════════════

    private WabaCheck checkWaba(WabaAccount waba, String accessToken) {
        try {
            MetaApiResponse response = metaApiClient.getWabaReviewStatus(waba.getWabaId(), accessToken);

            if (!response.isOk()) {
                return WabaCheck.builder()
                        .wabaId(waba.getWabaId())
                        .wabaAccountId(waba.getId())
                        .localStatus(waba.getStatus().getValue())
                        .status("CHECK_FAILED")
                        .detail("Meta API error: " + response.getErrorMessage())
                        .build();
            }

            // getWabaReviewStatus fields land in extras (flat response)
            String reviewStatus = strFromFlat(response, "account_review_status", "UNKNOWN");
            String templateNamespace = strFromFlat(response, "message_template_namespace", null);

            String checkStatus;
            String detail;

            switch (reviewStatus.toUpperCase()) {
                case "APPROVED" -> {
                    checkStatus = "OK";
                    detail = "WABA is approved and operational.";
                }
                case "PENDING" -> {
                    checkStatus = "REVIEW_PENDING";
                    detail = "WABA is under Meta review. Messaging may be limited until approved.";
                }
                case "REJECTED" -> {
                    checkStatus = "REJECTED";
                    detail = "WABA was rejected by Meta. Contact Meta Business Support.";
                }
                default -> {
                    checkStatus = "OK"; // Some WABAs don't have this field — not an error
                    detail = "Review status not available (normal for new accounts).";
                }
            }

            return WabaCheck.builder()
                    .wabaId(waba.getWabaId())
                    .wabaAccountId(waba.getId())
                    .reviewStatus(reviewStatus)
                    .localStatus(waba.getStatus().getValue())
                    .templateNamespace(templateNamespace)
                    .status(checkStatus)
                    .detail(detail)
                    .build();

        } catch (Exception ex) {
            log.warn("Health check: WABA check failed for wabaId={}: {}", waba.getWabaId(), ex.getMessage());
            return WabaCheck.builder()
                    .wabaId(waba.getWabaId())
                    .wabaAccountId(waba.getId())
                    .localStatus(waba.getStatus().getValue())
                    .status("CHECK_FAILED")
                    .detail("Exception during WABA check: " + ex.getMessage())
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════
    // CHECK 3 + 4: PHONE STATUS + BUSINESS PROFILE
    // ════════════════════════════════════════════════════════════

    private PhoneCheck checkPhone(WabaPhoneNumber phone, String accessToken) {
        String phoneId = phone.getPhoneNumberId();

        // ── Sub-check A: Phone number status ────────────────────────────
        String metaStatus = "UNKNOWN";
        String qualityRating = phone.getQualityRating() != null
                ? phone.getQualityRating().getValue() : "UNKNOWN";
        String verifiedName = phone.getVerifiedName();
        String checkStatus = "OK";
        String detail = "Phone number is connected.";

        try {
            MetaApiResponse statusResp = metaApiClient.getPhoneNumberStatus(phoneId, accessToken);

            if (statusResp.isOk()) {
                metaStatus = strFromFlat(statusResp, "status", "UNKNOWN").toUpperCase();
                qualityRating = strFromFlat(statusResp, "quality_rating", qualityRating).toUpperCase();
                verifiedName  = strFromFlat(statusResp, "verified_name", verifiedName);

                // Interpret status
                switch (metaStatus) {
                    case "CONNECTED" -> {
                        checkStatus = evaluateQuality(qualityRating);
                        detail = buildPhoneDetail(qualityRating, verifiedName);
                    }
                    case "DISCONNECTED" -> {
                        checkStatus = "DISCONNECTED";
                        detail = "Phone number is disconnected from Meta. Re-register the number.";
                    }
                    case "FLAGGED" -> {
                        checkStatus = "QUALITY_WARNING";
                        detail = "Phone number is FLAGGED by Meta — high spam block rate. Reduce message volume.";
                    }
                    case "RESTRICTED" -> {
                        checkStatus = "RESTRICTED";
                        detail = "Phone number is RESTRICTED by Meta — messaging is limited.";
                    }
                    default -> {
                        checkStatus = "OK";
                        detail = "Phone status: " + metaStatus;
                    }
                }
            } else {
                checkStatus = "CHECK_FAILED";
                detail = "Could not fetch phone status from Meta: " + statusResp.getErrorMessage();
            }
        } catch (Exception ex) {
            log.warn("Health check: phone status check failed for phoneId={}: {}", phoneId, ex.getMessage());
            checkStatus = "CHECK_FAILED";
            detail = "Exception during phone status check: " + ex.getMessage();
        }

        // ── Sub-check B: Business profile ────────────────────────────────
        Boolean profileExists = null;
        try {
            MetaApiResponse profileResp = metaApiClient.getBusinessProfile(phoneId, accessToken);
            profileExists = profileResp.isOk();

            if (!profileExists && "OK".equals(checkStatus)) {
                // Profile missing means permissions may be partially revoked
                checkStatus = "QUALITY_WARNING";
                detail += " WARNING: Business profile not found — permissions may be revoked.";
            }
        } catch (Exception ex) {
            log.debug("Health check: business profile check failed for phoneId={}: {}", phoneId, ex.getMessage());
            // Not fatal — profile check is supplementary
        }

        return PhoneCheck.builder()
                .phoneNumberId(phoneId)
                .displayPhoneNumber(phone.getDisplayPhoneNumber())
                .metaStatus(metaStatus)
                .qualityRating(qualityRating)
                .verifiedName(verifiedName)
                .profileExists(profileExists)
                .status(checkStatus)
                .detail(detail)
                .build();
    }

    // ════════════════════════════════════════════════════════════
    // OVERALL STATUS DERIVATION
    // ════════════════════════════════════════════════════════════

    /**
     * Overall status decision logic:
     *
     *   Token INVALID/EXPIRED         → UNHEALTHY  (messaging broken NOW)
     *   Token EXPIRING_SOON           → DEGRADED   (will break in <7 days)
     *   Any WABA REJECTED             → DEGRADED   (messaging limited)
     *   Any phone DISCONNECTED        → DEGRADED   (that phone can't send)
     *   Any phone RESTRICTED/FLAGGED  → DEGRADED   (quality issue)
     *   All checks OK                 → HEALTHY
     */
    private String deriveOverallStatus(TokenHealth tokenHealth,
                                       List<WabaCheck> wabaChecks,
                                       List<PhoneCheck> phoneChecks) {

        // Token is the backbone — if invalid, everything is broken
        if ("INVALID".equals(tokenHealth.getStatus()) || "EXPIRED".equals(tokenHealth.getStatus())) {
            return WabaHealthResponse.STATUS_UNHEALTHY;
        }

        boolean degraded = false;

        if ("EXPIRING_SOON".equals(tokenHealth.getStatus())) degraded = true;

        for (WabaCheck wc : wabaChecks) {
            if ("REJECTED".equals(wc.getStatus()) || "CHECK_FAILED".equals(wc.getStatus())) {
                degraded = true;
            }
        }

        for (PhoneCheck pc : phoneChecks) {
            String s = pc.getStatus();
            if ("DISCONNECTED".equals(s) || "RESTRICTED".equals(s)
                    || "QUALITY_WARNING".equals(s) || "CHECK_FAILED".equals(s)) {
                degraded = true;
            }
        }

        return degraded ? WabaHealthResponse.STATUS_DEGRADED : WabaHealthResponse.STATUS_HEALTHY;
    }

    private String buildSummary(String overallStatus, TokenHealth tokenHealth,
                                List<WabaCheck> wabaChecks,
                                List<PhoneCheck> phoneChecks) {
        return switch (overallStatus) {
            case WabaHealthResponse.STATUS_HEALTHY ->
                    "All checks passed. " + phoneChecks.size() + " phone number(s) connected and operational.";
            case WabaHealthResponse.STATUS_DEGRADED ->
                    "Integration is running but has issues. Review token and phone health details.";
            case WabaHealthResponse.STATUS_UNHEALTHY ->
                    "CRITICAL: Token is invalid. Messaging is broken. Customer must re-run embedded signup.";
            default -> "Health status could not be determined.";
        };
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════

    private String evaluateQuality(String qualityRating) {
        return switch (qualityRating.toUpperCase()) {
            case "RED"    -> "QUALITY_WARNING";
            case "YELLOW" -> "QUALITY_WARNING";
            default       -> "OK";
        };
    }

    private String buildPhoneDetail(String qualityRating, String verifiedName) {
        String base = "Phone connected";
        if (verifiedName != null && !verifiedName.isBlank()) {
            base += " as \"" + verifiedName + "\"";
        }
        return switch (qualityRating.toUpperCase()) {
            case "GREEN"  -> base + ". Quality: GREEN (good).";
            case "YELLOW" -> base + ". Quality: YELLOW — reduce spam to avoid ban.";
            case "RED"    -> base + ". Quality: RED — high ban risk! Pause marketing messages immediately.";
            default       -> base + ". Quality: " + qualityRating + ".";
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractScopes(Map<String, Object> data) {
        try {
            // Meta returns scopes as a list of granular_scopes objects or a plain scopes array
            Object scopesObj = data.get("scopes");
            if (scopesObj instanceof List<?> list) {
                return list.stream().map(Object::toString).collect(Collectors.toList());
            }
            // Some tokens return granular_scopes with { "scope": "whatsapp_business_management", ... }
            Object granular = data.get("granular_scopes");
            if (granular instanceof List<?> gList) {
                return gList.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> {
                            Map<String, Object> m = (Map<String, Object>) item;
                            return String.valueOf(m.getOrDefault("scope", ""));
                        })
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList());
            }
        } catch (Exception ex) {
            log.debug("Could not parse scopes from debug_token response: {}", ex.getMessage());
        }
        return List.of();
    }

    /** Read a string field from extras (flat response) with a default fallback. */
    private String strFromFlat(MetaApiResponse response, String key, String defaultValue) {
        Object val = response.getFlatValue(key);
        if (val == null || "null".equals(String.valueOf(val))) return defaultValue;
        String s = String.valueOf(val).trim();
        return s.isBlank() ? defaultValue : s;
    }
}