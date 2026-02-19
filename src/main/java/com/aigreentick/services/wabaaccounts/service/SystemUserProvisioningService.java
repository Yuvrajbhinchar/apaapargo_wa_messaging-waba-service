package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.SystemUserProvisioningResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.client.MetaApiRetryExecutor;
import com.aigreentick.services.wabaaccounts.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ══════════════════════════════════════════════════════════════════
 * PHASE 2 — System User Provisioning
 * ══════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * After Phase 1 (embedded signup), we hold a USER access token.
 * User tokens expire in 60 days — meaning every customer will
 * "disconnect" from WhatsApp every 60 days unless they re-auth.
 *
 * Production BSPs NEVER rely on user tokens for messaging.
 * Meta reviewers explicitly check for this during app review.
 *
 * The fix: provision a System User — a bot identity tied to your
 * Business Manager — and generate a permanent token for it.
 * Permanent tokens don't expire. This is the production standard.
 *
 * THE 3 META API CALLS (in order)
 * ────────────────────────────────
 * Step 1: Create System User
 *   POST /{businessId}/system_users
 *   → Creates a bot identity in the Business Manager
 *   → Returns systemUserId
 *
 * Step 2: Assign WABA to System User
 *   POST /{wabaId}/assigned_users
 *   → Grants the system user permission to manage the WABA
 *   → Task: "MANAGE" (needed for messaging + template management)
 *
 * Step 3: Generate Permanent System User Token
 *   POST /{systemUserId}/access_tokens
 *   → Issues a non-expiring token scoped to the system user
 *   → Requires appsecret_proof (HMAC-SHA256 of current token with app secret)
 *   → Replaces the 60-day user token in our DB
 *
 * RESULT
 * ──────
 * MetaOAuthAccount.accessToken  ← replaced with permanent system user token
 * MetaOAuthAccount.expiresAt    ← set to null (permanent = never expires)
 * MetaOAuthAccount.systemUserId ← stored for audit and future management
 * MetaOAuthAccount.tokenType    ← set to SYSTEM_USER
 *
 * WHEN TO CALL THIS
 * ─────────────────
 * Option A: Immediately after Phase 1 embedded signup (recommended)
 *   → EmbeddedSignupService calls provisionSystemUser() after saveOnboardingData()
 *
 * Option B: Manual trigger via API (for orgs already onboarded via Phase 1)
 *   → POST /api/v1/system-users/provision/{organizationId}
 *
 * IMPORTANT CONSTRAINT
 * ─────────────────────
 * The user performing the embedded signup MUST be an Admin of the
 * Business Manager. System user creation requires Admin access.
 * If the user is only an Employee, Step 1 will fail.
 * → Solution: Use your platform's own Business Manager (not customer's)
 *   and configure it as a Tech Partner with asset access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemUserProvisioningService {

    private final MetaApiClient metaApiClient;
    private final MetaApiRetryExecutor metaRetry;
    private final MetaApiConfig metaApiConfig;
    private final MetaOAuthAccountRepository oauthAccountRepository;
    private final WabaAccountRepository wabaAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;

    private static final String SYSTEM_USER_NAME = "AiGreenTick-Bot";
    private static final String SYSTEM_USER_ROLE = "ADMIN";   // Must be ADMIN to manage WABA assets
    private static final String HMAC_ALGO         = "HmacSHA256";

    // The scopes the system user token needs
    private static final String REQUIRED_SCOPES =
            "whatsapp_business_management,whatsapp_business_messaging,business_management";

    // ════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Full Phase 2 provisioning for an organization.
     *
     * Idempotent: if the org already has a system user token, this is a no-op
     * (unless forceRefresh=true is passed).
     *
     * @param organizationId  the org to provision
     * @param forceRefresh    if true, re-provisions even if system user already exists
     */
    public SystemUserProvisioningResponse provisionForOrganization(Long organizationId,
                                                                   boolean forceRefresh) {
        log.info("Starting Phase 2 system user provisioning: orgId={}, forceRefresh={}",
                organizationId, forceRefresh);

        // Load the org's OAuth account (must exist — Phase 1 must be done first)
        MetaOAuthAccount oauthAccount = oauthAccountRepository
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new InvalidRequestException(
                        "No OAuth account found for organization " + organizationId +
                                ". Complete Phase 1 (embedded signup) first."));

        // Idempotency check — skip if already provisioned
        if (!forceRefresh && MetaOAuthAccount.TokenType.SYSTEM_USER.equals(oauthAccount.getTokenType())) {
            log.info("Organization {} already has a system user token. Skipping.", organizationId);
            return SystemUserProvisioningResponse.alreadyProvisioned(
                    organizationId, oauthAccount.getSystemUserId());
        }

        // Load all WABAs for this org
        List<WabaAccount> wabas = wabaAccountRepository.findByOrganizationId(organizationId);
        if (wabas.isEmpty()) {
            throw new InvalidRequestException(
                    "No WABA accounts found for organization " + organizationId);
        }

        // GAP 4: Decrypt the stored token before using it for Meta API calls
        // Tokens are stored encrypted — decrypt to get the raw token for API use
        String userToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
        String businessManagerId = resolveBusinessManagerId(wabas, userToken);

        // ── Step 1: Create System User ─────────────────────────────────────────
        // Creates a bot identity in the Business Manager.
        // If the system user already exists (forceRefresh case), Meta returns the existing one.
        String systemUserId = createSystemUser(businessManagerId, userToken);
        log.info("System user ready: systemUserId={}, businessManagerId={}", systemUserId, businessManagerId);

        // ── Step 2: Assign each WABA to the System User ────────────────────────
        // The system user needs MANAGE permission on every WABA to send messages
        // and manage templates on behalf of this org.
        int assignedCount = 0;
        for (WabaAccount waba : wabas) {
            try {
                assignWabaToSystemUser(waba.getWabaId(), systemUserId, userToken);
                assignedCount++;
                log.info("WABA {} assigned to system user {}", waba.getWabaId(), systemUserId);
            } catch (Exception ex) {
                // Log but continue — don't fail provisioning because of one WABA
                log.error("Failed to assign WABA {} to system user {}: {}",
                        waba.getWabaId(), systemUserId, ex.getMessage());
            }
        }

        if (assignedCount == 0) {
            throw new InvalidRequestException(
                    "Failed to assign any WABA to the system user. " +
                            "Ensure the user token has Admin permission in Business Manager " + businessManagerId);
        }

        // ── Step 3: Generate Permanent System User Token ───────────────────────
        // Requires appsecret_proof = HMAC-SHA256(currentToken, appSecret)
        // This prevents token generation without knowing the app secret.
        String appSecretProof = computeAppSecretProof(userToken);
        String permanentToken = generateSystemUserToken(systemUserId, appSecretProof, userToken);
        log.info("Permanent system user token generated for orgId={}", organizationId);

        // ── Step 4: Replace user token with permanent token in DB ──────────────
        saveSystemUserToken(oauthAccount, systemUserId, permanentToken);
        log.info("Phase 2 complete: orgId={}, systemUserId={}, WABAs assigned={}",
                organizationId, systemUserId, assignedCount);

        return SystemUserProvisioningResponse.success(
                organizationId, systemUserId, assignedCount, wabas.size());
    }

    /**
     * Convenience method for calling Phase 2 directly after Phase 1.
     * Swallows errors so Phase 1 success is never blocked by Phase 2 failure.
     *
     * @return true if provisioning succeeded, false if it failed (non-fatal)
     */
    public boolean tryProvisionAfterSignup(Long organizationId) {
        try {
            SystemUserProvisioningResponse result =
                    provisionForOrganization(organizationId, false);
            return result.isSuccess();
        } catch (Exception ex) {
            log.warn("Phase 2 auto-provisioning failed (non-fatal). " +
                            "Trigger manually via POST /api/v1/system-users/provision/{}. Error: {}",
                    organizationId, ex.getMessage());
            return false;
        }
    }

    /**
     * Check if an org already has a permanent system user token.
     * Used by the status endpoint.
     */
    public boolean hasSystemUserToken(Long organizationId) {
        return oauthAccountRepository
                .findByOrganizationId(organizationId)
                .map(MetaOAuthAccount::hasPermanentToken)
                .orElse(false);
    }

    /**
     * Provision all organizations that still have 60-day user tokens.
     * Used for bulk migration of existing customers to permanent tokens.
     *
     * Non-transactional — each org is provisioned independently so one
     * failure doesn't block others.
     */
    public BulkProvisioningResult provisionAllPendingOrgs() {
        var pendingOrgs = oauthAccountRepository.findAllUserTokenAccounts();
        log.info("Bulk provisioning: {} orgs found with user tokens", pendingOrgs.size());

        int succeeded = 0;
        int failed = 0;

        for (MetaOAuthAccount account : pendingOrgs) {
            try {
                provisionForOrganization(account.getOrganizationId(), false);
                succeeded++;
                log.info("Bulk provision succeeded for orgId={}", account.getOrganizationId());
            } catch (Exception ex) {
                failed++;
                log.error("Bulk provision failed for orgId={}: {}",
                        account.getOrganizationId(), ex.getMessage());
            }
        }

        log.info("Bulk provisioning complete: {} succeeded, {} failed", succeeded, failed);
        return new BulkProvisioningResult(pendingOrgs.size(), succeeded, failed);
    }

    /** Result record for bulk provisioning */
    public record BulkProvisioningResult(int attempted, int succeeded, int failed) {}

    // ════════════════════════════════════════════════════════════
    // PRIVATE — META API STEPS
    // ════════════════════════════════════════════════════════════

    /**
     * Step 1: Create (or retrieve) a system user in the Business Manager.
     *
     * Meta API: POST /{businessId}/system_users
     * Body: { name: "AiGreenTick-Bot", role: "ADMIN" }
     *
     * If a system user with the same name already exists, Meta returns
     * the existing one — this call is effectively idempotent.
     */
    private String createSystemUser(String businessManagerId, String accessToken) {
        log.info("Creating system user in Business Manager: {}", businessManagerId);

        var response = metaApiClient.createSystemUser(
                businessManagerId,
                SYSTEM_USER_NAME,
                SYSTEM_USER_ROLE,
                accessToken
        );

        if (!Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) {
            throw new InvalidRequestException(
                    "Failed to create system user in Business Manager " + businessManagerId +
                            ". Ensure the access token has Admin permission in the Business Manager.");
        }

        Object id = response.getData().get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new InvalidRequestException(
                    "Meta returned no system user ID. Check Business Manager permissions.");
        }

        return String.valueOf(id);
    }

    /**
     * Step 2: Assign a WABA to the system user with MANAGE permissions.
     *
     * Meta API: POST /{wabaId}/assigned_users
     * Body: { user: systemUserId, tasks: ["MANAGE"] }
     *
     * MANAGE task allows:
     *   - Sending messages
     *   - Managing message templates
     *   - Reading phone number details
     *   - Subscribing to webhooks
     */
    private void assignWabaToSystemUser(String wabaId, String systemUserId, String accessToken) {
        log.info("Assigning WABA {} to system user {}", wabaId, systemUserId);

        var response = metaApiClient.assignWabaToSystemUser(
                wabaId,
                systemUserId,
                accessToken
        );

        if (!Boolean.TRUE.equals(response.getSuccess())) {
            throw new InvalidRequestException(
                    "Failed to assign WABA " + wabaId + " to system user " + systemUserId);
        }
    }

    /**
     * Step 3: Generate a permanent access token for the system user.
     *
     * Meta API: POST /{systemUserId}/access_tokens
     * Body: {
     *   business_app: appId,
     *   appsecret_proof: hmacSha256(currentToken, appSecret),
     *   scope: "whatsapp_business_management,...",
     * }
     *
     * The appsecret_proof is a security requirement — Meta won't issue
     * a system user token without proving you know the app secret.
     * This prevents token theft from ever being used to issue more tokens.
     *
     * Returns: a permanent token that NEVER EXPIRES.
     */
    private String generateSystemUserToken(String systemUserId, String appSecretProof, String accessToken) {
        log.info("Generating permanent token for system user: {}", systemUserId);

        var response = metaApiClient.generateSystemUserToken(
                systemUserId,
                metaApiConfig.getAppId(),
                appSecretProof,
                REQUIRED_SCOPES,
                accessToken
        );

        if (!Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) {
            throw new InvalidRequestException(
                    "Failed to generate permanent system user token. " +
                            "Ensure the Meta App is in Live mode and has the required permissions approved.");
        }

        Object token = response.getData().get("access_token");
        if (token == null || String.valueOf(token).isBlank()) {
            throw new InvalidRequestException(
                    "Meta returned empty permanent token. " +
                            "Check app permissions: whatsapp_business_management, " +
                            "whatsapp_business_messaging, business_management must all be approved.");
        }

        return String.valueOf(token);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE — DB + HELPERS
    // ════════════════════════════════════════════════════════════

    /**
     * Persist the permanent token, marking the account as SYSTEM_USER type.
     * Sets expiresAt=null because system user tokens never expire.
     */
    @Transactional
    public void saveSystemUserToken(MetaOAuthAccount oauthAccount,
                                    String systemUserId,
                                    String permanentToken) {
        // GAP 4: Encrypt permanent token before storing — never store plaintext
        oauthAccount.setAccessToken(tokenEncryptionService.encrypt(permanentToken));
        oauthAccount.setExpiresAt(null);                              // Permanent — never expires
        oauthAccount.setSystemUserId(systemUserId);
        oauthAccount.setTokenType(MetaOAuthAccount.TokenType.SYSTEM_USER);
        oauthAccountRepository.save(oauthAccount);

        log.info("Permanent encrypted token saved for orgId={}, systemUserId={}",
                oauthAccount.getOrganizationId(), systemUserId);
    }

    /**
     * Compute HMAC-SHA256 signature of the current token using the app secret.
     * Required by Meta as appsecret_proof when generating system user tokens.
     * Prevents misuse if a token is ever intercepted.
     */
    private String computeAppSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    metaApiConfig.getAppSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return HexFormat.of().formatHex(
                    mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new InvalidRequestException(
                    "Failed to compute appsecret_proof: " + ex.getMessage());
        }
    }

    /**
     * Resolve the Business Manager ID needed to create the system user.
     * We try to get it from WABA details if not already known.
     * Falls back to requesting it from Meta.
     */
    private String resolveBusinessManagerId(List<WabaAccount> wabas, String accessToken) {
        // Try to get it from the first WABA's Meta details
        WabaAccount firstWaba = wabas.get(0);

        try {
            var wabaDetails = metaApiClient.getWabaDetails(firstWaba.getWabaId(), accessToken);
            if (Boolean.TRUE.equals(wabaDetails.getSuccess()) && wabaDetails.getData() != null) {
                Object bizId = wabaDetails.getData().get("business_id");
                if (bizId != null && !String.valueOf(bizId).isBlank()) {
                    log.debug("Business Manager ID resolved from WABA {}: {}",
                            firstWaba.getWabaId(), bizId);
                    return String.valueOf(bizId);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not resolve Business Manager ID from WABA details: {}", ex.getMessage());
        }

        throw new InvalidRequestException(
                "Could not determine Business Manager ID for organization. " +
                        "Ensure the WABA is properly connected and the token has business_management scope.");
    }
}