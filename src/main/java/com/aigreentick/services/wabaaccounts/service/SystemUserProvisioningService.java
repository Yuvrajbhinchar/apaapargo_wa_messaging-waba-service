package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.client.MetaApiRetryExecutor;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.SystemUserProvisioningResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
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
 * PHASE 2 — System User Provisioning
 *
 * ─── BLOCKER 1 FIX: generateSystemUserToken() ───────────────────────────
 * Meta's POST /{systemUserId}/access_tokens returns a FLAT response:
 *   { "access_token": "EAA...", "token_type": "bearer" }
 *
 * "access_token" lands in extras via @JsonAnySetter — NOT in data wrapper.
 * OLD BROKEN: response.getData().get("access_token") → getData() = null → NPE
 * NEW FIXED:  response.getFlatValue("access_token")  → checks extras first
 *
 * Same fix applied to resolveBusinessManagerId — business_id is also flat.
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
    private static final String SYSTEM_USER_ROLE = "ADMIN";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String REQUIRED_SCOPES =
            "whatsapp_business_management,whatsapp_business_messaging,business_management";

    // ════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════

    public SystemUserProvisioningResponse provisionForOrganization(Long organizationId,
                                                                   boolean forceRefresh) {
        log.info("Starting Phase 2 provisioning: orgId={}, forceRefresh={}", organizationId, forceRefresh);

        MetaOAuthAccount oauthAccount = oauthAccountRepository
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new InvalidRequestException(
                        "No OAuth account found for organization " + organizationId +
                                ". Complete Phase 1 (embedded signup) first."));

        if (!forceRefresh && MetaOAuthAccount.TokenType.SYSTEM_USER.equals(oauthAccount.getTokenType())) {
            log.info("Organization {} already has a system user token. Skipping.", organizationId);
            return SystemUserProvisioningResponse.alreadyProvisioned(
                    organizationId, oauthAccount.getSystemUserId());
        }

        List<WabaAccount> wabas = wabaAccountRepository.findByOrganizationId(organizationId);
        if (wabas.isEmpty()) {
            throw new InvalidRequestException(
                    "No WABA accounts found for organization " + organizationId);
        }

        String userToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
        String businessManagerId = resolveBusinessManagerId(wabas, userToken);

        // Step 1: Create System User
        String systemUserId = createSystemUser(businessManagerId, userToken);
        log.info("System user ready: systemUserId={}, businessManagerId={}", systemUserId, businessManagerId);

        // Step 2: Assign each WABA
        int assignedCount = 0;
        for (WabaAccount waba : wabas) {
            try {
                assignWabaToSystemUser(waba.getWabaId(), systemUserId, userToken);
                assignedCount++;
                log.info("WABA {} assigned to system user {}", waba.getWabaId(), systemUserId);
            } catch (Exception ex) {
                log.error("Failed to assign WABA {} to system user {}: {}",
                        waba.getWabaId(), systemUserId, ex.getMessage());
            }
        }

        if (assignedCount == 0) {
            throw new InvalidRequestException(
                    "Failed to assign any WABA to the system user. " +
                            "Ensure the token has Admin permission in Business Manager " + businessManagerId);
        }

        // Step 3: Generate Permanent Token
        String appSecretProof = computeAppSecretProof(userToken);
        String permanentToken = generateSystemUserToken(systemUserId, appSecretProof, userToken);
        log.info("Permanent system user token generated for orgId={}", organizationId);

        // Step 4: Save to DB
        saveSystemUserToken(oauthAccount, systemUserId, permanentToken);
        log.info("Phase 2 complete: orgId={}, systemUserId={}, WABAs assigned={}/{}",
                organizationId, systemUserId, assignedCount, wabas.size());

        return SystemUserProvisioningResponse.success(
                organizationId, systemUserId, assignedCount, wabas.size());
    }

    public boolean tryProvisionAfterSignup(Long organizationId) {
        try {
            SystemUserProvisioningResponse result = provisionForOrganization(organizationId, false);
            return result.isSuccess();
        } catch (Exception ex) {
            log.warn("Phase 2 auto-provisioning failed (non-fatal). " +
                            "Trigger manually via POST /api/v1/system-users/provision/{}. Error: {}",
                    organizationId, ex.getMessage());
            return false;
        }
    }

    public boolean hasSystemUserToken(Long organizationId) {
        return oauthAccountRepository
                .findByOrganizationId(organizationId)
                .map(MetaOAuthAccount::hasPermanentToken)
                .orElse(false);
    }

    public BulkProvisioningResult provisionAllPendingOrgs() {
        var pendingOrgs = oauthAccountRepository.findAllUserTokenAccounts();
        log.info("Bulk provisioning: {} orgs with user tokens", pendingOrgs.size());

        int succeeded = 0, failed = 0;
        for (MetaOAuthAccount account : pendingOrgs) {
            try {
                provisionForOrganization(account.getOrganizationId(), false);
                succeeded++;
            } catch (Exception ex) {
                failed++;
                log.error("Bulk provision failed for orgId={}: {}",
                        account.getOrganizationId(), ex.getMessage());
            }
        }
        return new BulkProvisioningResult(pendingOrgs.size(), succeeded, failed);
    }

    public record BulkProvisioningResult(int attempted, int succeeded, int failed) {}

    // ════════════════════════════════════════════════════════════
    // PRIVATE — META API STEPS
    // ════════════════════════════════════════════════════════════

    /**
     * Step 1: Create System User.
     * POST /{businessId}/system_users
     * Returns: { "id": "sys_user_123" } — flat response, id lands in extras.
     */
    private String createSystemUser(String businessManagerId, String accessToken) {
        log.info("Creating system user in Business Manager: {}", businessManagerId);

        var response = metaApiClient.createSystemUser(
                businessManagerId,
                SYSTEM_USER_NAME,
                SYSTEM_USER_ROLE,
                accessToken
        );

        String systemUserId = response.getId();

        if (!response.isOk() || systemUserId == null || systemUserId.isBlank()) {
            throw new InvalidRequestException(
                    "Failed to create system user in Business Manager " + businessManagerId +
                            ". Ensure the access token has Admin permission in the Business Manager." +
                            (response.getErrorMessage() != null
                                    ? " Meta error: " + response.getErrorMessage()
                                    : ""));
        }

        return systemUserId;
    }

    private void assignWabaToSystemUser(String wabaId,
                                        String systemUserId,
                                        String accessToken) {
        log.info("Assigning WABA {} to system user {}", wabaId, systemUserId);

        var response = metaApiClient.assignWabaToSystemUser(
                wabaId, systemUserId, accessToken);

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Failed to assign WABA " + wabaId + " to system user " + systemUserId +
                            (response.getErrorMessage() != null
                                    ? ": " + response.getErrorMessage()
                                    : ""));
        }
    }

    /**
     * Step 3: Generate permanent token for system user.
     *
     * POST /{systemUserId}/access_tokens returns FLAT response:
     *   { "access_token": "EAA...", "token_type": "bearer" }
     *
     * ════════════════════════════════════════════════════════════
     * BLOCKER 1 FIX:
     * ════════════════════════════════════════════════════════════
     * OLD BROKEN CODE:
     *   if (response.getData() == null) { throw ... }        ← getData() always null for flat
     *   Object token = response.getData().get("access_token") ← NPE guaranteed
     *
     * NEW FIXED CODE:
     *   Object token = response.getFlatValue("access_token")  ← checks extras first
     *
     * getFlatValue() checks extras (where flat fields land via @JsonAnySetter)
     * then falls back to dataAsMap. This matches how exchangeCodeForToken and
     * extendToLongLivedToken correctly read their flat responses.
     * ════════════════════════════════════════════════════════════
     */
    private String generateSystemUserToken(String systemUserId,
                                           String appSecretProof,
                                           String accessToken) {
        log.info("Generating permanent token for system user: {}", systemUserId);

        var response = metaApiClient.generateSystemUserToken(
                systemUserId,
                metaApiConfig.getAppId(),
                appSecretProof,
                REQUIRED_SCOPES,
                accessToken
        );

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Failed to generate permanent system user token." +
                            (response.getErrorMessage() != null
                                    ? " Meta error: " + response.getErrorMessage()
                                    : " Ensure the Meta App is in Live mode with required permissions approved."));
        }

        // ═══ BLOCKER 1 FIX ═══
        // Meta returns { "access_token": "...", "token_type": "bearer" } as FLAT JSON.
        // "access_token" lands in extras via @JsonAnySetter — NOT in data wrapper.
        // getFlatValue() checks extras first, then dataAsMap fallback.
        Object token = response.getFlatValue("access_token");

        if (token == null || String.valueOf(token).isBlank()
                || "null".equals(String.valueOf(token))) {
            log.error("System user token generation returned no access_token. " +
                    "Extras: {}, Data: {}", response.getExtras(), response.getDataAsMap());
            throw new InvalidRequestException(
                    "Meta returned an empty permanent token. " +
                            "Check app permissions: whatsapp_business_management, " +
                            "whatsapp_business_messaging, business_management must all be approved.");
        }

        return String.valueOf(token);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE — DB + HELPERS
    // ════════════════════════════════════════════════════════════

    @Transactional
    public void saveSystemUserToken(MetaOAuthAccount oauthAccount,
                                    String systemUserId,
                                    String permanentToken) {
        oauthAccount.setAccessToken(tokenEncryptionService.encrypt(permanentToken));
        oauthAccount.setExpiresAt(null); // Permanent — never expires
        oauthAccount.setSystemUserId(systemUserId);
        oauthAccount.setTokenType(MetaOAuthAccount.TokenType.SYSTEM_USER);
        oauthAccountRepository.save(oauthAccount);

        log.info("Permanent encrypted token saved for orgId={}, systemUserId={}",
                oauthAccount.getOrganizationId(), systemUserId);
    }

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
     * Resolve Business Manager ID from WABA details.
     * GET /{wabaId}?fields=business_id returns FLAT object.
     * business_id lands in extras → use getFlatValue()
     */
    private String resolveBusinessManagerId(List<WabaAccount> wabas, String accessToken) {
        WabaAccount firstWaba = wabas.get(0);

        try {
            MetaApiResponse wabaDetails = metaApiClient.getWabaDetails(firstWaba.getWabaId(), accessToken);

            if (wabaDetails.isOk()) {
                Object bizId = wabaDetails.getFlatValue("business_id");
                if (bizId != null && !String.valueOf(bizId).isBlank() && !"null".equals(String.valueOf(bizId))) {
                    log.info("Business Manager ID resolved from WABA {}: {}", firstWaba.getWabaId(), bizId);
                    return String.valueOf(bizId);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not resolve Business Manager ID from WABA details: {}", ex.getMessage());
        }

        throw new InvalidRequestException(
                "Could not determine Business Manager ID for WABA " + firstWaba.getWabaId() + ". " +
                        "Ensure the token has business_management scope and the WABA is properly connected.");
    }
}