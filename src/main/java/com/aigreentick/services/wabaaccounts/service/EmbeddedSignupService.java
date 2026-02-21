package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.client.MetaApiRetryExecutor;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.constants.OnboardingStep;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.dto.response.SignupConfigResponse;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.exception.TaskOwnershipLostException;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.security.TokenEncryptionService;
import com.aigreentick.services.wabaaccounts.service.OnboardingModel.PersistedOnboardingData;
import com.aigreentick.services.wabaaccounts.service.OnboardingModel.TokenResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedSignupService {

    private final MetaApiClient metaApiClient;
    private final MetaApiRetryExecutor metaRetry;
    private final MetaApiConfig metaApiConfig;
    private final WabaAccountRepository wabaAccountRepository;
    private final PhoneNumberService phoneNumberService;
    private final PhoneRegistrationService phoneRegistrationService;
    private final SystemUserProvisioningService systemUserProvisioningService;
    private final OnboardingPersistenceService onboardingPersistenceService;
    private final OnboardingTaskStateService taskStateService;
    private final TokenEncryptionService tokenEncryptionService;

    private static final List<String> REQUIRED_SCOPES = List.of(
            "whatsapp_business_management",
            "whatsapp_business_messaging",
            "business_management"
    );
    private static final String REQUIRED_SCOPES_CSV = String.join(",", REQUIRED_SCOPES);
    private static final long MIN_LONG_LIVED_SECONDS = 7L * 24 * 3600;

    // ═══════════════════════════════════════════════════════════
    // CONFIG (unchanged)
    // ═══════════════════════════════════════════════════════════

    public SignupConfigResponse getSignupConfig() {
        return SignupConfigResponse.builder()
                .metaAppId(metaApiConfig.getAppId())
                .apiVersion(metaApiConfig.getApiVersion())
                .scopes(REQUIRED_SCOPES_CSV)
                .extrasJson(buildExtrasJson())
                .callbackEndpoint("/api/v1/embedded-signup/callback")
                .configId(metaApiConfig.getEmbeddedSignupConfigId())
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // MAIN SAGA — step-based, resumable, no artificial timeout
    // ═══════════════════════════════════════════════════════════

    /**
     * Check if this worker still owns the task.
     * Called between major phases to detect cancellation/reset early.
     *
     * If the task is no longer PROCESSING, the user cancelled it or
     * the scheduler reset it. Continuing would waste Meta API calls
     * and potentially create orphaned resources.
     */
    private void assertOwnership(Long taskId) {
        if (!taskStateService.verifyOwnership(taskId)) {
            OnboardingTask task = taskStateService.loadTaskState(taskId);
            throw new TaskOwnershipLostException(taskId, task.getStatus().name());
        }
    }

    // ── UPDATED processSignupCallback with ownership checks ──────

    public EmbeddedSignupResponse processSignupCallback(EmbeddedSignupCallbackRequest request,
                                                        Long taskId) {
        log.info("Signup saga started: taskId={}, orgId={}, coexistence={}",
                taskId, request.getOrganizationId(), request.isCoexistenceFlow());

        OnboardingTask taskState = taskStateService.loadTaskState(taskId);

        // ── Phase 1: Token (irreversible) ──
        TokenResult tokenResult = executeTokenPhase(request, taskId, taskState);

        assertOwnership(taskId);  // ← check before Phase 2

        // ── Phase 2: Discovery ──
        String wabaId = executeWabaResolution(request, taskId, taskState, tokenResult.accessToken());
        String bmId = executeBmResolution(request, taskId, taskState, wabaId, tokenResult.accessToken());
        String phoneId = executePhoneResolution(request, taskId, taskState, wabaId, tokenResult.accessToken());

        assertOwnership(taskId);  // ← check before persistence

        // ── Phase 3: Persist credentials ──
        PersistedOnboardingData saved = executeCredentialPersistence(
                request, taskId, taskState, tokenResult, wabaId, bmId);

        assertOwnership(taskId);  // ← check before best-effort externals

        // ── Phase 4: Best-effort setup ──
        boolean webhookOk = executeWebhookSubscription(taskId, taskState, wabaId, tokenResult.accessToken());

        assertOwnership(taskId);  // ← check between external calls

        List<PhoneNumberResponse> phoneNumbers = executePhoneSync(taskId, taskState, saved, tokenResult.accessToken());
        executePhoneRegistration(taskId, taskState, phoneId, tokenResult.accessToken());
        executeSmbSync(request, taskId, taskState, phoneId, tokenResult.accessToken());

        assertOwnership(taskId);  // ← check before Phase 2 provisioning (heaviest call)

        executePhase2(request, taskId, taskState);

        log.info("Signup saga completed: taskId={}, wabaId={}, phones={}",
                taskId, wabaId, phoneNumbers.size());

        return EmbeddedSignupResponse.builder()
                .wabaAccountId(saved.waba().getId())
                .wabaId(wabaId)
                .status(saved.waba().getStatus().getValue())
                .businessManagerId(bmId)
                .tokenExpiresIn(tokenResult.expiresIn())
                .longLivedToken(tokenResult.isLongLived())
                .phoneNumbers(phoneNumbers)
                .phoneNumberCount(phoneNumbers.size())
                .webhookSubscribed(webhookOk)
                .summary(buildSummary(phoneNumbers.size(), wabaId, webhookOk))
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // STEP A+B: Token Phase — CRITICAL: persist immediately
    // ═══════════════════════════════════════════════════════════

    private TokenResult executeTokenPhase(EmbeddedSignupCallbackRequest request,
                                          Long taskId, OnboardingTask taskState) {

        // ── RESUME CHECK: If token was already acquired in a previous attempt,
        //    reload it from the task instead of re-exchanging the consumed code.
        if (taskState.isStepCompleted(OnboardingStep.TOKEN_EXTENSION)
                && taskState.getEncryptedAccessToken() != null) {

            log.info("Task {}: token already acquired — resuming from saved state", taskId);
            String decryptedToken = tokenEncryptionService.decrypt(taskState.getEncryptedAccessToken());
            long expiresIn = taskState.getTokenExpiresIn() != null ? taskState.getTokenExpiresIn() : 0L;
            return new TokenResult(decryptedToken, expiresIn, expiresIn >= MIN_LONG_LIVED_SECONDS);
        }

        // ── Step A: Exchange code → short-lived token ──
        String shortLivedToken;
        if (taskState.isStepCompleted(OnboardingStep.TOKEN_EXCHANGE)
                && taskState.getEncryptedAccessToken() != null) {
            // Code was exchanged but extension failed — reuse short-lived token
            log.info("Task {}: code already exchanged — reusing short-lived token", taskId);
            shortLivedToken = tokenEncryptionService.decrypt(taskState.getEncryptedAccessToken());
        } else {
            shortLivedToken = exchangeCodeForToken(request.getCode());

            // ╔══════════════════════════════════════════════════════════╗
            // ║  CRITICAL: Persist token IMMEDIATELY after exchange.    ║
            // ║  The OAuth code is now consumed. If we crash before     ║
            // ║  this save, the token is lost and retry is impossible.  ║
            // ║  REQUIRES_NEW ensures this commit survives any outer    ║
            // ║  transaction rollback.                                  ║
            // ╚══════════════════════════════════════════════════════════╝
            String encryptedShortToken = tokenEncryptionService.encrypt(shortLivedToken);
            taskStateService.persistStepResult(taskId, OnboardingStep.TOKEN_EXCHANGE, task -> {
                task.setEncryptedAccessToken(encryptedShortToken);
            });
            log.info("Task {}: OAuth code exchanged — short-lived token persisted to DB", taskId);
        }

        // ── Step B: Extend to long-lived token ──
        TokenResult tokenResult = extendToLongLivedToken(shortLivedToken);

        // Persist long-lived token immediately (overwrites short-lived)
        String encryptedLongToken = tokenEncryptionService.encrypt(tokenResult.accessToken());
        taskStateService.persistStepResult(taskId, OnboardingStep.TOKEN_EXTENSION, task -> {
            task.setEncryptedAccessToken(encryptedLongToken);
            task.setTokenExpiresIn(tokenResult.expiresIn());
        });
        log.info("Task {}: long-lived token persisted ({} days expiry)",
                taskId, tokenResult.expiresIn() / 86400);

        // ── Step C: Verify scopes (non-fatal) ──
        if (!taskState.isStepCompleted(OnboardingStep.SCOPE_VERIFICATION)) {
            verifyOAuthScopes(tokenResult.accessToken());
            taskStateService.persistStepCompleted(taskId, OnboardingStep.SCOPE_VERIFICATION);
        }

        return tokenResult;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP D: WABA Resolution — resumable
    // ═══════════════════════════════════════════════════════════

    private String executeWabaResolution(EmbeddedSignupCallbackRequest request,
                                         Long taskId, OnboardingTask taskState,
                                         String accessToken) {

        if (taskState.isStepCompleted(OnboardingStep.WABA_RESOLUTION)
                && taskState.getResolvedWabaId() != null) {
            log.info("Task {}: WABA already resolved — {}", taskId, taskState.getResolvedWabaId());
            return taskState.getResolvedWabaId();
        }

        String wabaId = resolveAndVerifyWabaOwnership(request, accessToken);

        taskStateService.persistStepResult(taskId, OnboardingStep.WABA_RESOLUTION, task -> {
            task.setResolvedWabaId(wabaId);
        });

        return wabaId;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP E: Business Manager Resolution — resumable
    // ═══════════════════════════════════════════════════════════

    private String executeBmResolution(EmbeddedSignupCallbackRequest request,
                                       Long taskId, OnboardingTask taskState,
                                       String wabaId, String accessToken) {

        if (taskState.isStepCompleted(OnboardingStep.BM_RESOLUTION)
                && taskState.getResolvedBusinessManagerId() != null) {
            log.info("Task {}: BM already resolved — {}", taskId, taskState.getResolvedBusinessManagerId());
            return taskState.getResolvedBusinessManagerId();
        }

        String bmId = resolveBusinessManagerId(request, wabaId, accessToken);

        taskStateService.persistStepResult(taskId, OnboardingStep.BM_RESOLUTION, task -> {
            task.setResolvedBusinessManagerId(bmId);
        });

        return bmId;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP F: Phone Number Resolution — resumable
    // ═══════════════════════════════════════════════════════════

    private String executePhoneResolution(EmbeddedSignupCallbackRequest request,
                                          Long taskId, OnboardingTask taskState,
                                          String wabaId, String accessToken) {

        if (taskState.isStepCompleted(OnboardingStep.PHONE_RESOLUTION)
                && taskState.getResolvedPhoneNumberId() != null) {
            log.info("Task {}: phone already resolved — {}", taskId, taskState.getResolvedPhoneNumberId());
            return taskState.getResolvedPhoneNumberId();
        }

        String phoneId = resolvePhoneNumberId(request, wabaId, accessToken);

        if (phoneId != null) {
            taskStateService.persistStepResult(taskId, OnboardingStep.PHONE_RESOLUTION, task -> {
                task.setResolvedPhoneNumberId(phoneId);
            });
        } else {
            taskStateService.persistStepCompleted(taskId, OnboardingStep.PHONE_RESOLUTION);
        }

        return phoneId;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP G: Credential Persistence — resumable
    // ═══════════════════════════════════════════════════════════

    private PersistedOnboardingData executeCredentialPersistence(
            EmbeddedSignupCallbackRequest request, Long taskId,
            OnboardingTask taskState, TokenResult tokenResult,
            String wabaId, String businessManagerId) {

        if (taskState.isStepCompleted(OnboardingStep.CREDENTIAL_PERSISTENCE)
                && taskState.getResultWabaAccountId() != null) {
            log.info("Task {}: credentials already persisted — wabaAccountId={}",
                    taskId, taskState.getResultWabaAccountId());

            // Reload from DB for subsequent steps
            var waba = wabaAccountRepository.findById(taskState.getResultWabaAccountId())
                    .orElseThrow(() -> new InvalidRequestException(
                            "WabaAccount " + taskState.getResultWabaAccountId() + " not found on resume"));
            return new PersistedOnboardingData(waba, null);
        }

        PersistedOnboardingData saved = onboardingPersistenceService.persistOnboardingData(
                request, tokenResult, wabaId, businessManagerId);

        taskStateService.persistStepResult(taskId, OnboardingStep.CREDENTIAL_PERSISTENCE, task -> {
            task.setResultWabaAccountId(saved.waba().getId());
        });

        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // STEPS G2-J: Best-effort external setup (idempotent guards)
    // ═══════════════════════════════════════════════════════════

    private boolean executeWebhookSubscription(Long taskId, OnboardingTask taskState,
                                               String wabaId, String accessToken) {
        if (taskState.isStepCompleted(OnboardingStep.WEBHOOK_SUBSCRIBE)) {
            log.info("Task {}: webhook already subscribed — skipping", taskId);
            return true;
        }

        boolean ok = subscribeAndVerifyWebhook(wabaId, accessToken);

        // Always mark complete — webhook subscribe is idempotent on Meta's side
        // and we don't want to re-run it on retry even if it "failed" (best-effort)
        taskStateService.persistStepCompleted(taskId, OnboardingStep.WEBHOOK_SUBSCRIBE);
        return ok;
    }

    private List<PhoneNumberResponse> executePhoneSync(Long taskId, OnboardingTask taskState,
                                                       PersistedOnboardingData saved,
                                                       String accessToken) {
        if (taskState.isStepCompleted(OnboardingStep.PHONE_SYNC)) {
            log.info("Task {}: phone sync already done — loading from DB", taskId);
            try {
                return phoneNumberService.getPhoneNumbersByWaba(saved.waba().getId());
            } catch (Exception ex) {
                return List.of();
            }
        }

        List<PhoneNumberResponse> phoneNumbers = new ArrayList<>();
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(saved.waba(), accessToken);
            phoneNumbers = phoneNumberService.getPhoneNumbersByWaba(saved.waba().getId());
        } catch (Exception ex) {
            log.warn("Task {}: phone sync failed (retry via /{}/sync): {}",
                    taskId, saved.waba().getId(), ex.getMessage());
        }

        taskStateService.persistStepCompleted(taskId, OnboardingStep.PHONE_SYNC);
        return phoneNumbers;
    }

    private void executePhoneRegistration(Long taskId, OnboardingTask taskState,
                                          String phoneNumberId, String accessToken) {
        if (phoneNumberId == null) return;

        if (taskState.isStepCompleted(OnboardingStep.PHONE_REGISTRATION)) {
            log.info("Task {}: phone already registered — skipping", taskId);
            return;
        }

        boolean registered = phoneRegistrationService.registerPhoneNumber(phoneNumberId, accessToken);
        if (registered) {
            log.info("Task {}: phone registered: {}", taskId, phoneNumberId);
        } else {
            log.warn("Task {}: phone registration failed: {}", taskId, phoneNumberId);
        }

        // Mark complete regardless — registration is best-effort and
        // "already registered" is handled inside registerPhoneNumber()
        taskStateService.persistStepCompleted(taskId, OnboardingStep.PHONE_REGISTRATION);
    }

    private void executeSmbSync(EmbeddedSignupCallbackRequest request, Long taskId,
                                OnboardingTask taskState, String phoneNumberId,
                                String accessToken) {
        if (!request.isCoexistenceFlow() || phoneNumberId == null) return;

        if (taskState.isStepCompleted(OnboardingStep.SMB_SYNC)) {
            log.info("Task {}: SMB sync already done — skipping", taskId);
            return;
        }

        log.info("Task {}: coexistence flow — initiating SMB sync: {}", taskId, phoneNumberId);
        phoneRegistrationService.initiateSmbSync(phoneNumberId, accessToken);

        taskStateService.persistStepCompleted(taskId, OnboardingStep.SMB_SYNC);
    }

    private void executePhase2(EmbeddedSignupCallbackRequest request, Long taskId,
                               OnboardingTask taskState) {
        if (taskState.isStepCompleted(OnboardingStep.PHASE2_PROVISIONING)) {
            log.info("Task {}: Phase 2 already done — skipping", taskId);
            return;
        }

        boolean ok = systemUserProvisioningService.tryProvisionAfterSignup(request.getOrganizationId());
        if (ok) {
            log.info("Task {}: Phase 2 complete: orgId={}", taskId, request.getOrganizationId());
        }

        // Mark complete even on failure — Phase 2 has its own retry via scheduler
        taskStateService.persistStepCompleted(taskId, OnboardingStep.PHASE2_PROVISIONING);
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS — unchanged from original
    // (moved here for completeness, same logic)
    // ═══════════════════════════════════════════════════════════

    private String exchangeCodeForToken(String code) {
        var response = metaRetry.execute("exchangeCode",
                () -> metaApiClient.exchangeCodeForToken(code));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Failed to exchange OAuth code: " + response.getErrorMessage());
        }

        Object token = response.getFlatValue("access_token");
        if (token == null || token.toString().isBlank()) {
            log.error("Token exchange response extras: {}", response.getExtras());
            throw new InvalidRequestException("Meta returned empty access token during code exchange.");
        }
        return token.toString();
    }

    private TokenResult extendToLongLivedToken(String shortLivedToken) {
        try {
            var response = metaRetry.execute("extendToken",
                    () -> metaApiClient.extendAccessToken(shortLivedToken));

            if (!response.isOk()) {
                throw new InvalidRequestException(
                        "Meta rejected token extension: " + response.getErrorMessage());
            }

            Object tokenObj = response.getFlatValue("access_token");
            Object expiresObj = response.getFlatValue("expires_in");

            if (tokenObj == null || tokenObj.toString().isBlank() || "null".equals(tokenObj.toString())) {
                throw new InvalidRequestException("Meta returned blank token during extension.");
            }

            String longToken = tokenObj.toString();
            long expiresIn = parseLong(expiresObj != null ? expiresObj : 0L);

            if (expiresIn < MIN_LONG_LIVED_SECONDS) {
                throw new InvalidRequestException(String.format(
                        "Received short-lived token (%d seconds). Ensure Meta App is in Live mode.", expiresIn));
            }

            return new TokenResult(longToken, expiresIn, true);
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidRequestException("Failed to extend token: " + ex.getMessage());
        }
    }

    private void verifyOAuthScopes(String accessToken) {
        try {
            var response = metaRetry.execute("getPermissions",
                    () -> metaApiClient.getUserPermissions(accessToken));

            if (!response.isOk()) {
                log.warn("Could not verify scopes: {} — proceeding", response.getErrorMessage());
                return;
            }

            List<Map<String, Object>> permissions = response.getDataAsList();
            if (permissions == null) {
                log.warn("Unexpected /me/permissions format — skipping scope check");
                return;
            }

            List<String> granted = permissions.stream()
                    .filter(p -> "granted".equals(p.get("status")))
                    .map(p -> String.valueOf(p.get("permission")))
                    .toList();

            List<String> missing = REQUIRED_SCOPES.stream()
                    .filter(s -> !granted.contains(s))
                    .toList();

            if (!missing.isEmpty()) {
                throw new InvalidRequestException(
                        "Missing required permissions: " + missing + ". Required: " + REQUIRED_SCOPES);
            }
            log.info("OAuth scope check passed. Granted: {}", granted);
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Scope check failed unexpectedly (proceeding): {}", ex.getMessage());
        }
    }

    private String resolveAndVerifyWabaOwnership(EmbeddedSignupCallbackRequest request,
                                                 String accessToken) {
        if (request.getWabaId() != null && !request.getWabaId().isBlank()) {
            verifyWabaOwnership(request.getWabaId(), accessToken);
            return request.getWabaId();
        }
        log.info("No wabaId in request — auto-discovering");
        return discoverWabaId(accessToken);
    }

    private void verifyWabaOwnership(String wabaId, String accessToken) {
        var response = metaRetry.executeWithContext("verifyWabaOwnership", wabaId,
                () -> metaApiClient.getWabaDetails(wabaId, accessToken));
        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Cannot verify access to WABA " + wabaId + ": " + response.getErrorMessage());
        }
        log.info("WABA ownership confirmed: wabaId={}", wabaId);
    }

    @SuppressWarnings("unchecked")
    private String discoverWabaId(String accessToken) {
        var response = metaRetry.execute("discoverWabaId",
                () -> metaApiClient.getBusinessAccounts(accessToken));

        if (!response.isOk()) {
            throw new InvalidRequestException(
                    "Could not fetch business accounts: " + response.getErrorMessage());
        }

        List<Map<String, Object>> businesses = response.getDataAsList();
        if (businesses == null || businesses.isEmpty()) {
            throw new InvalidRequestException("No Business Managers found for this token.");
        }

        for (Map<String, Object> biz : businesses) {
            Object wabaDataObj = biz.get("owned_whatsapp_business_accounts");
            if (wabaDataObj instanceof Map) {
                Map<String, Object> wabaData = (Map<String, Object>) wabaDataObj;
                Object wabas = wabaData.get("data");
                if (wabas instanceof List) {
                    List<Map<String, Object>> wabaList = (List<Map<String, Object>>) wabas;
                    if (!wabaList.isEmpty()) {
                        String wabaId = String.valueOf(wabaList.get(0).get("id"));
                        log.info("WABA auto-discovered: wabaId={}", wabaId);
                        return wabaId;
                    }
                }
            }
        }
        throw new InvalidRequestException("No WABA found in any Business Manager.");
    }

    private String resolveBusinessManagerId(EmbeddedSignupCallbackRequest request,
                                            String wabaId, String accessToken) {
        if (request.getBusinessManagerId() != null && !request.getBusinessManagerId().isBlank()) {
            return request.getBusinessManagerId();
        }
        try {
            var details = metaRetry.executeWithContext("getWabaDetails", wabaId,
                    () -> metaApiClient.getWabaDetails(wabaId, accessToken));
            if (details.isOk()) {
                Object bizId = details.getFlatValue("business_id");
                if (bizId != null && !String.valueOf(bizId).isBlank()) {
                    log.info("Business Manager ID resolved: {}", bizId);
                    return String.valueOf(bizId);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not resolve Business Manager ID: {}", ex.getMessage());
        }
        return "UNKNOWN-" + wabaId;
    }

    private String resolvePhoneNumberId(EmbeddedSignupCallbackRequest request,
                                        String wabaId, String accessToken) {
        if (request.hasPhoneNumberId()) {
            log.info("Using phone number ID from frontend: {}", request.getPhoneNumberId());
            return request.getPhoneNumberId();
        }

        log.info("Phone number ID not provided. Auto-discovering from WABA: {}", wabaId);
        PhoneRegistrationService.DiscoveredPhone discovered =
                phoneRegistrationService.discoverLatestPhoneNumber(wabaId, accessToken);

        if (discovered == null) {
            log.warn("No phone numbers found for WABA {}. Registration skipped.", wabaId);
            return null;
        }

        log.info("Auto-discovered phone: id={}, display={}",
                discovered.phoneNumberId(), discovered.displayPhoneNumber());
        return discovered.phoneNumberId();
    }

    private boolean subscribeAndVerifyWebhook(String wabaId, String accessToken) {
        try {
            var subResult = metaRetry.executeWithContext("subscribeWebhook", wabaId,
                    () -> metaApiClient.subscribeWabaToWebhook(wabaId, accessToken));
            if (!subResult.isOk()) {
                log.warn("Webhook subscribe failed: wabaId={}, error={}", wabaId, subResult.getErrorMessage());
                return false;
            }
        } catch (Exception ex) {
            log.warn("Webhook subscribe threw: wabaId={}, error={}", wabaId, ex.getMessage());
            return false;
        }

        try {
            var check = metaRetry.executeWithContext("verifyWebhook", wabaId,
                    () -> metaApiClient.getSubscribedApps(wabaId, accessToken));
            boolean active = isOurAppSubscribed(check);
            if (active) {
                log.info("Webhook subscription confirmed: wabaId={}", wabaId);
            } else {
                log.warn("Webhook subscribed but app not confirmed: wabaId={}", wabaId);
            }
            return active;
        } catch (Exception ex) {
            log.warn("Webhook health check failed: wabaId={}", wabaId);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isOurAppSubscribed(MetaApiResponse response) {
        if (!response.isOk()) return false;
        try {
            List<Map<String, Object>> apps = response.getDataAsList();
            if (apps == null) return false;
            String ourAppId = metaApiConfig.getAppId();
            return apps.stream().anyMatch(app ->
                    ourAppId.equals(String.valueOf(app.get("id"))) ||
                            ourAppId.equals(String.valueOf(app.get("whatsapp_business_api_data"))));
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildExtrasJson() {
        return "{\"feature\": \"whatsapp_embedded_signup\", \"version\": 2, \"sessionInfoVersion\": \"3\"}";
    }

    private String buildSummary(int phoneCount, String wabaId, boolean webhookOk) {
        String webhookNote = webhookOk ? "" : " Warning: webhook not confirmed.";
        if (phoneCount == 0) {
            return "WABA connected: " + wabaId + ". No phone numbers found." + webhookNote;
        }
        return "WABA connected with " + phoneCount + " phone number" +
                (phoneCount > 1 ? "s" : "") + ". Ready to send!" + webhookNote;
    }

    private long parseLong(Object value) {
        if (value == null) return 0L;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ex) { return 0L; }
    }

    public boolean isOrganizationConnected(Long organizationId) {
        return !wabaAccountRepository
                .findByOrganizationIdAndStatus(organizationId, WabaStatus.ACTIVE)
                .isEmpty();
    }
}
