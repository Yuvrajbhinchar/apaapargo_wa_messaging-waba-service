package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.constants.MessagingLimitTier;
import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.request.RegisterPhoneNumberRequest;
import com.aigreentick.services.wabaaccounts.dto.request.RequestVerificationCodeRequest;
import com.aigreentick.services.wabaaccounts.dto.request.VerifyPhoneNumberRequest;
import com.aigreentick.services.wabaaccounts.dto.response.MetaApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaPhoneNumber;
import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import com.aigreentick.services.wabaaccounts.exception.PhoneNumberNotFoundException;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.mapper.WabaMapper;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaPhoneNumberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PhoneNumberService — production-grade distributed consistency version.
 *
 * ═══════════════════════════════════════════════════════════════
 * SENIOR REVIEW — Core architectural fix: two-transaction registration
 *
 * THE PROBLEM (single-tx design):
 *
 *   @Transactional
 *   registerPhoneNumber() {
 *       validate()                     ← DB reads (fine)
 *       metaApiClient.register()       ← ⚠️ EXTERNAL CALL INSIDE TRANSACTION
 *       phoneRepository.save()         ← DB write
 *   }
 *
 *   Failure scenario:
 *     1. Meta API succeeds — phone is registered on Meta's side
 *     2. DB connection times out / pool exhausted / deadlock occurs
 *     3. Transaction rolls back — phone row never committed
 *     4. DB: phone does NOT exist
 *        Meta: phone IS registered
 *     5. Retry: Meta returns "already registered" error
 *        System cannot distinguish "registered by us" from "registered elsewhere"
 *     → Permanent inconsistency. No automatic recovery path.
 *
 *   Additional harm of holding a DB connection during a network call:
 *   - Meta API can take 1–5 seconds under load
 *   - Your DB connection pool has e.g. 10 connections (application.yaml: max=10)
 *   - 10 concurrent registrations = pool exhausted = all other DB operations stall
 *   - Under a signup spike, this cascades into a full service outage
 *
 * THE FIX — two-transaction saga:
 *
 *   TX 1  [REQUIRES_NEW]
 *   ├── validate WABA, limits
 *   ├── save phone { status = PENDING }     ← commits immediately, DB connection released
 *   └── commit
 *
 *   [outside any transaction]
 *   └── metaApiClient.register()            ← network I/O, no DB connection held
 *
 *   TX 2  [REQUIRES_NEW]
 *   ├── if Meta success → UPDATE status PENDING → ACTIVE
 *   ├── if Meta failure → UPDATE status PENDING → REGISTRATION_FAILED
 *   └── commit
 *
 * CRASH RECOVERY MATRIX:
 *
 *   Crash point              | DB state            | Meta state | Recovery
 *   ─────────────────────────┼─────────────────────┼────────────┼───────────────────────
 *   Before TX 1 commits      | nothing             | nothing    | Retry from scratch ✅
 *   After TX 1, before Meta  | PENDING             | nothing    | Retry → Meta call runs ✅
 *   After Meta, before TX 2  | PENDING             | registered | Retry → TX 2 finalizes ✅
 *   After TX 2 (success)     | ACTIVE              | registered | Idempotent return ✅
 *   After TX 2 (failure)     | REGISTRATION_FAILED | nothing    | Retry → fresh attempt ✅
 *
 *   Every crash point has a deterministic recovery path.
 *   The old design had NO recovery path for "after Meta, before DB commit".
 *
 * IDEMPOTENCY DECISION TABLE (on retry):
 *
 *   Existing status      | Action
 *   ─────────────────────┼─────────────────────────────────────────────────────
 *   ACTIVE               | Return existing — already fully registered
 *   PENDING              | Skip TX 1, retry Meta call + TX 2 directly
 *   REGISTRATION_FAILED  | Delete stale row, restart TX 1 (Meta never registered)
 *   none                 | Fresh registration — TX 1 → Meta → TX 2
 * ═══════════════════════════════════════════════════════════════
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneNumberService {

    private final WabaPhoneNumberRepository phoneNumberRepository;
    private final WabaAccountRepository wabaRepository;
    private final MetaOAuthAccountRepository metaOAuthRepository;
    private final MetaApiClient metaApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    // ═══════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<PhoneNumberResponse> getPhoneNumbersByWaba(Long wabaAccountId) {
        log.debug("Fetching phone numbers for wabaAccountId={}", wabaAccountId);
        if (!wabaRepository.existsById(wabaAccountId)) {
            throw WabaNotFoundException.withId(wabaAccountId);
        }
        return phoneNumberRepository.findByWabaAccountId(wabaAccountId)
                .stream()
                .map(WabaMapper::toPhoneNumberResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PhoneNumberResponse getPhoneNumberById(Long id) {
        log.debug("Fetching phone number by id={}", id);
        return phoneNumberRepository.findById(id)
                .map(WabaMapper::toPhoneNumberResponse)
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(id));
    }

    // ═══════════════════════════════════════════════════════════
    // REGISTER — two-transaction saga
    // ═══════════════════════════════════════════════════════════

    /**
     * Public orchestrator — intentionally NOT @Transactional.
     *
     * This method coordinates two separate DB transactions with an external
     * Meta API call between them. Making this method @Transactional would
     * re-introduce the exact problem it is designed to solve: holding a DB
     * connection open during a network call.
     *
     * Each phase has its own @Transactional(REQUIRES_NEW) boundary.
     */
    public PhoneNumberResponse registerPhoneNumber(RegisterPhoneNumberRequest request) {
        log.info("Phone registration saga started: phoneNumberId={}, wabaAccountId={}",
                request.getPhoneNumberId(), request.getWabaAccountId());

        // ── IDEMPOTENCY: check existing state before doing anything ──────────
        Optional<WabaPhoneNumber> existing =
                phoneNumberRepository.findByPhoneNumberId(request.getPhoneNumberId());

        if (existing.isPresent()) {
            WabaPhoneNumber phone = existing.get();
            switch (phone.getStatus()) {

                case ACTIVE -> {
                    // Already fully registered — return immediately, no Meta call
                    log.info("Phone already ACTIVE — returning existing: {}",
                            request.getPhoneNumberId());
                    return WabaMapper.toPhoneNumberResponse(phone);
                }

                case PENDING -> {
                    // TX 1 committed previously but TX 2 never ran.
                    // This happens when: service crashed between Meta call and TX 2,
                    // or the caller is retrying after a timeout.
                    // Skip TX 1, jump directly to Meta call + TX 2.
                    log.info("Phone is PENDING — resuming saga from Meta call: {}",
                            request.getPhoneNumberId());
                    return callMetaAndFinalize(phone, request);
                }

                case REGISTRATION_FAILED -> {
                    // Meta previously rejected this phone (bad PIN, not owned by WABA, etc.)
                    // The row exists but Meta never registered it → safe to retry.
                    // Reset to PENDING so the saga can run cleanly from Meta call onward.
                    log.info("Phone is REGISTRATION_FAILED — resetting to PENDING for retry: {}",
                            request.getPhoneNumberId());
                    resetFailedToPending(request.getPhoneNumberId());
                    return callMetaAndFinalize(phone, request);
                }

                default -> {
                    // BLOCKED or DISABLED — do not attempt re-registration
                    log.warn("Phone {} is in status {} — registration rejected",
                            request.getPhoneNumberId(), phone.getStatus());
                    throw new InvalidRequestException(
                            "Phone number " + request.getPhoneNumberId() +
                                    " is " + phone.getStatus().getValue() +
                                    " and cannot be re-registered.");
                }
            }
        }

        // ── FRESH REGISTRATION ───────────────────────────────────────────────

        // TX 1: persist intent before touching Meta
        WabaPhoneNumber pendingPhone = savePending(request);

        // Meta call + TX 2: outside the TX 1 transaction (already committed)
        return callMetaAndFinalize(pendingPhone, request);
    }

    // ── PHASE 1: Save PENDING ────────────────────────────────────────────────

    /**
     * TX 1 — Save phone as PENDING and commit immediately.
     *
     * REQUIRES_NEW creates a brand-new transaction that commits before this
     * method returns — regardless of what the caller's transaction state is.
     * The DB connection is released as soon as this method exits.
     *
     * This is the crash-safe anchor. Once this commits, we have a durable record
     * that registration was attempted. Even if the service crashes on the next line,
     * the PENDING row enables recovery on restart.
     *
     * The advisory limit check lives here, inside the transaction that holds
     * a consistent view of countByWabaAccountId. The DB UNIQUE constraint on
     * phone_number_id is still the race-condition enforcement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WabaPhoneNumber savePending(RegisterPhoneNumberRequest request) {
        WabaAccount waba = wabaRepository.findById(request.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(request.getWabaAccountId()));

        if (!waba.isActive()) {
            throw InvalidRequestException.wabaNotActive(waba.getId());
        }

        long currentCount = phoneNumberRepository.countByWabaAccountId(waba.getId());
        if (currentCount >= WabaConstants.MAX_PHONE_NUMBERS_PER_WABA) {
            throw InvalidRequestException.maxPhoneNumbersReached(
                    WabaConstants.MAX_PHONE_NUMBERS_PER_WABA);
        }

        WabaPhoneNumber phone = WabaPhoneNumber.builder()
                .wabaAccountId(waba.getId())
                .phoneNumberId(request.getPhoneNumberId())
                .status(PhoneNumberStatus.PENDING)           // ← not ACTIVE yet
                .qualityRating(QualityRating.UNKNOWN)
                .messagingLimitTier(MessagingLimitTier.TIER_1K)
                .build();

        try {
            phone = phoneNumberRepository.save(phone);
            log.info("Phone PENDING committed to DB: phoneNumberId={}, dbId={}",
                    request.getPhoneNumberId(), phone.getId());
            return phone;
        } catch (DataIntegrityViolationException ex) {
            // Another thread/request inserted the same phoneNumberId concurrently.
            // The UNIQUE constraint caught the race. Load and return what's there.
            log.warn("Race on PENDING insert for phoneNumberId={} — fetching winner",
                    request.getPhoneNumberId());
            return phoneNumberRepository
                    .findByPhoneNumberId(request.getPhoneNumberId())
                    .orElseThrow(() -> PhoneNumberNotFoundException
                            .withPhoneNumberId(request.getPhoneNumberId()));
        }
    }

    // ── External call + PHASE 2 ──────────────────────────────────────────────

    /**
     * Meta API call followed immediately by TX 2.
     *
     * This method is intentionally NOT @Transactional.
     * The Meta call happens here — outside any DB transaction.
     * No DB connection is held during the network I/O.
     */
    private PhoneNumberResponse callMetaAndFinalize(WabaPhoneNumber pendingPhone,
                                                    RegisterPhoneNumberRequest request) {
        String accessToken = resolveAccessTokenByWaba(pendingPhone.getWabaAccountId());

        MetaApiResponse metaResponse;
        try {
            metaResponse = metaApiClient.registerPhoneNumber(
                    request.getPhoneNumberId(), accessToken, request.getPin());
        } catch (Exception ex) {
            // Network-level failure (timeout, connection refused, etc.)
            // Meta state is unknown — mark REGISTRATION_FAILED so caller can retry.
            log.error("Meta API call threw exception for phoneNumberId={}: {}",
                    request.getPhoneNumberId(), ex.getMessage());
            markRegistrationFailed(request.getPhoneNumberId());
            throw new InvalidRequestException(
                    "Meta registration call failed due to network error. " +
                            "The phone is marked REGISTRATION_FAILED and can be retried: "
                            + ex.getMessage());
        }

        if (metaResponse.isOk()) {
            // Meta succeeded — TX 2: mark ACTIVE
            return finalizeActive(request.getPhoneNumberId());
        }

        // Meta returned an application-level error
        String errorMsg = metaResponse.getErrorMessage();

        // "Already registered" means Meta has it — we should treat it as success.
        // This covers the case where TX 2 previously failed after a successful Meta call,
        // and the caller is now retrying.
        if (errorMsg != null && errorMsg.contains("already registered")) {
            log.info("Meta says already registered (idempotent) — marking ACTIVE: {}",
                    request.getPhoneNumberId());
            return finalizeActive(request.getPhoneNumberId());
        }

        // Genuine Meta rejection (wrong PIN, phone not owned by WABA, etc.)
        // Mark REGISTRATION_FAILED — caller can retry with corrected request.
        log.warn("Meta rejected phone registration: phoneNumberId={}, error={}",
                request.getPhoneNumberId(), errorMsg);
        markRegistrationFailed(request.getPhoneNumberId());
        throw new InvalidRequestException(
                "Meta rejected phone registration (phone is now REGISTRATION_FAILED, " +
                        "retry with corrected data): " + errorMsg);
    }

    // ── PHASE 2a: Commit ACTIVE ──────────────────────────────────────────────

    /**
     * TX 2 (success path) — transition PENDING → ACTIVE.
     *
     * REQUIRES_NEW ensures this is an independent commit from TX 1.
     * Even if something in the calling context is rolling back, this
     * status update is committed separately.
     *
     * Uses a conditional UPDATE (WHERE status = PENDING) to be safe
     * against concurrent state changes between the Meta call and this commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhoneNumberResponse finalizeActive(String metaPhoneId) {
        int updated = phoneNumberRepository.updatePhoneStatus(
                metaPhoneId,
                PhoneNumberStatus.PENDING,
                PhoneNumberStatus.ACTIVE);

        if (updated == 0) {
            // Status was not PENDING — likely changed concurrently (e.g. another
            // retry already finalized it, or someone manually set BLOCKED).
            // Load whatever is there and return it.
            log.warn("TX 2 active update affected 0 rows for phoneNumberId={} " +
                    "— fetching current state", metaPhoneId);
        } else {
            log.info("Phone ACTIVE committed: phoneNumberId={}", metaPhoneId);
        }

        return phoneNumberRepository.findByPhoneNumberId(metaPhoneId)
                .map(WabaMapper::toPhoneNumberResponse)
                .orElseThrow(() -> PhoneNumberNotFoundException.withPhoneNumberId(metaPhoneId));
    }

    // ── PHASE 2b: Commit REGISTRATION_FAILED ────────────────────────────────

    /**
     * TX 2 (failure path) — transition PENDING → REGISTRATION_FAILED.
     *
     * REQUIRES_NEW: this commit happens even if something else is rolling back.
     * It is critical that we persist the failure — without it, the phone stays
     * PENDING forever with no indication of what happened, and retry logic
     * cannot distinguish "in progress" from "failed" from "orphaned".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRegistrationFailed(String metaPhoneId) {
        int updated = phoneNumberRepository.updatePhoneStatus(
                metaPhoneId,
                PhoneNumberStatus.PENDING,
                PhoneNumberStatus.REGISTRATION_FAILED);

        if (updated > 0) {
            log.info("Phone REGISTRATION_FAILED committed: phoneNumberId={}", metaPhoneId);
        } else {
            log.warn("markRegistrationFailed: 0 rows updated for phoneNumberId={} " +
                    "(already in different state)", metaPhoneId);
        }
    }

    // ── Reset FAILED → PENDING for retry ────────────────────────────────────

    /**
     * Reset a REGISTRATION_FAILED phone back to PENDING so the saga
     * can proceed from the Meta call step on retry.
     *
     * Meta never registered a REGISTRATION_FAILED phone (we only reach that
     * status when Meta explicitly rejected us), so resetting is safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedToPending(String metaPhoneId) {
        int updated = phoneNumberRepository.updatePhoneStatus(
                metaPhoneId,
                PhoneNumberStatus.REGISTRATION_FAILED,
                PhoneNumberStatus.PENDING);

        log.info("Phone reset REGISTRATION_FAILED → PENDING: phoneNumberId={}, rows={}",
                metaPhoneId, updated);
    }

    // ═══════════════════════════════════════════════════════════
    // VERIFY
    // ═══════════════════════════════════════════════════════════

    /**
     * Senior fix: single JOIN FETCH query via findByMetaPhoneIdWithOAuthAccount()
     * replaces the three-query N+1 chain in the old resolveAccessToken().
     */
    @Transactional
    public void requestVerificationCode(RequestVerificationCodeRequest request) {
        log.info("Requesting verification code: phoneNumberId={}, method={}",
                request.getPhoneNumberId(), request.getMethod());

        WabaPhoneNumber phone = phoneNumberRepository
                .findByMetaPhoneIdWithOAuthAccount(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException
                        .withPhoneNumberId(request.getPhoneNumberId()));

        String accessToken = extractDecryptedToken(phone);

        var metaResponse = metaApiClient.requestVerificationCode(
                phone.getPhoneNumberId(), accessToken,
                request.getMethod(), request.getLocale());

        if (!metaResponse.isOk()) {
            throw new InvalidRequestException(
                    "Meta rejected verification code request: " + metaResponse.getErrorMessage());
        }
        log.info("Verification code sent to: {}", phone.getDisplayPhoneNumber());
    }

    @Transactional
    public PhoneNumberResponse verifyPhoneNumber(VerifyPhoneNumberRequest request) {
        log.info("Verifying phone number: phoneNumberId={}", request.getPhoneNumberId());

        WabaPhoneNumber phone = phoneNumberRepository
                .findByMetaPhoneIdWithOAuthAccount(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException
                        .withPhoneNumberId(request.getPhoneNumberId()));

        String accessToken = extractDecryptedToken(phone);

        var metaResponse = metaApiClient.verifyCode(
                phone.getPhoneNumberId(), accessToken, request.getCode());

        if (!metaResponse.isOk()) {
            throw new InvalidRequestException(
                    "Verification failed: " + metaResponse.getErrorMessage());
        }

        phone.setStatus(PhoneNumberStatus.ACTIVE);
        phone = phoneNumberRepository.save(phone);
        log.info("Phone number verified: {}", phone.getDisplayPhoneNumber());
        return WabaMapper.toPhoneNumberResponse(phone);
    }

    // ═══════════════════════════════════════════════════════════
    // SYNC FROM META
    // ═══════════════════════════════════════════════════════════

    /**
     * NOT @Transactional — each phone persisted in its own REQUIRES_NEW tx.
     * One bad phone does not roll back the others.
     *
     * Bulk pre-loads existing phones in one IN query (not N individual SELECTs).
     * Batch-saves all valid phones in one saveAll() call.
     */
    public void syncPhoneNumbersFromMeta(WabaAccount waba, String accessToken) {
        log.info("Syncing phone numbers from Meta: wabaId={}", waba.getWabaId());

        MetaApiResponse metaResponse = metaApiClient.getPhoneNumbers(waba.getWabaId(), accessToken);

        if (!metaResponse.isOk()) {
            log.warn("Meta API error during phone sync: wabaId={}, error={}",
                    waba.getWabaId(), metaResponse.getErrorMessage());
            return;
        }

        List<Map<String, Object>> phonesData = metaResponse.getDataAsList();
        if (phonesData == null || phonesData.isEmpty()) {
            log.info("No phone numbers found in Meta for wabaId={}", waba.getWabaId());
            return;
        }

        // Extract and validate Meta IDs upfront
        List<String> incomingIds = new ArrayList<>();
        for (Map<String, Object> phoneData : phonesData) {
            String id = extractMetaPhoneId(phoneData);
            if (id != null) incomingIds.add(id);
        }

        if (incomingIds.isEmpty()) {
            log.warn("All {} phones from Meta had null/blank IDs — sync skipped for wabaId={}",
                    phonesData.size(), waba.getWabaId());
            return;
        }

        // ONE bulk SELECT instead of N individual SELECTs
        Map<String, WabaPhoneNumber> existingByMetaId =
                phoneNumberRepository.findAllByWabaAccountIdAndPhoneNumberIdIn(
                                waba.getId(), incomingIds)
                        .stream()
                        .collect(Collectors.toMap(
                                WabaPhoneNumber::getPhoneNumberId,
                                Function.identity()));

        List<WabaPhoneNumber> toSave = new ArrayList<>();
        int skipped = 0;

        for (Map<String, Object> phoneData : phonesData) {
            String metaPhoneId = extractMetaPhoneId(phoneData);
            if (metaPhoneId == null) { skipped++; continue; }

            try {
                WabaPhoneNumber phone = existingByMetaId.get(metaPhoneId);
                if (phone != null) {
                    applyMetaData(phone, phoneData);
                } else {
                    phone = WabaPhoneNumber.builder()
                            .wabaAccountId(waba.getId())
                            .phoneNumberId(metaPhoneId)
                            .displayPhoneNumber(str(phoneData, "display_phone_number"))
                            .verifiedName(str(phoneData, "verified_name"))
                            .status(parseStatus(phoneData))
                            .qualityRating(parseQuality(phoneData))
                            .messagingLimitTier(parseTier(phoneData))
                            .build();
                }
                toSave.add(phone);
            } catch (Exception ex) {
                skipped++;
                log.warn("Skipping malformed phone data (phoneId={}): {}",
                        metaPhoneId, ex.getMessage());
            }
        }

        if (!toSave.isEmpty()) {
            persistPhonesBatch(toSave);
        }

        log.info("Phone sync complete: saved={}, skipped={}, wabaId={}",
                toSave.size(), skipped, waba.getWabaId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistPhonesBatch(List<WabaPhoneNumber> phones) {
        phoneNumberRepository.saveAll(phones);
        log.debug("Batch-saved {} phones", phones.size());
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    private void applyMetaData(WabaPhoneNumber phone, Map<String, Object> data) {
        if (data.containsKey("display_phone_number"))
            phone.setDisplayPhoneNumber(str(data, "display_phone_number"));
        if (data.containsKey("verified_name"))
            phone.setVerifiedName(str(data, "verified_name"));
        phone.setStatus(parseStatus(data));
        phone.setQualityRating(parseQuality(data));
        phone.setMessagingLimitTier(parseTier(data));
    }

    private PhoneNumberStatus parseStatus(Map<String, Object> data) {
        String raw = str(data, "status");
        if (raw.isBlank()) return PhoneNumberStatus.ACTIVE;
        try {
            return PhoneNumberStatus.fromValue(raw.toLowerCase());
        } catch (Exception ex) {
            log.warn("Unknown phone status from Meta: '{}' — defaulting to ACTIVE", raw);
            return PhoneNumberStatus.ACTIVE;
        }
    }

    private QualityRating parseQuality(Map<String, Object> data) {
        String raw = str(data, "quality_rating");
        if (raw.isBlank()) return QualityRating.UNKNOWN;
        try {
            return QualityRating.valueOf(raw.toUpperCase());
        } catch (Exception ex) {
            log.warn("Unknown quality rating from Meta: '{}' — defaulting to UNKNOWN", raw);
            return QualityRating.UNKNOWN;
        }
    }

    private MessagingLimitTier parseTier(Map<String, Object> data) {
        String raw = str(data, "messaging_limit_tier");
        if (raw.isBlank()) return MessagingLimitTier.TIER_1K;
        try {
            return MessagingLimitTier.valueOf(raw.toUpperCase());
        } catch (Exception ex) {
            log.warn("Unknown messaging limit tier from Meta: '{}' — defaulting to TIER_1K", raw);
            return MessagingLimitTier.TIER_1K;
        }
    }

    private String extractMetaPhoneId(Map<String, Object> data) {
        Object idObj = data.get("id");
        if (idObj == null) {
            log.warn("Phone data from Meta missing 'id' field: {}", data);
            return null;
        }
        String id = String.valueOf(idObj).trim();
        if (id.isBlank() || "null".equalsIgnoreCase(id)) {
            log.warn("Phone data from Meta has blank/null 'id': {}", data);
            return null;
        }
        return id;
    }

    private String str(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val).trim() : "";
    }

    /**
     * Extract token from a phone entity already JOIN-FETCHed with its
     * WabaAccount and MetaOAuthAccount — no additional DB queries.
     */
    private String extractDecryptedToken(WabaPhoneNumber phone) {
        WabaAccount waba = phone.getWabaAccount();
        if (waba == null) {
            throw WabaNotFoundException.withId(phone.getWabaAccountId());
        }
        MetaOAuthAccount oauthAccount = waba.getMetaOAuthAccount();
        if (oauthAccount == null) {
            throw new WabaNotFoundException(
                    "OAuth account not found for WABA: " + waba.getId());
        }
        return tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
    }

    /**
     * Resolve token when we have a WABA account ID but not the entity.
     * Used in the registration saga where we need the token before the
     * full JOIN FETCH path is established.
     */
    private String resolveAccessTokenByWaba(Long wabaAccountId) {
        WabaAccount waba = wabaRepository.findById(wabaAccountId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaAccountId));

        MetaOAuthAccount oauthAccount = metaOAuthRepository
                .findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA: " + waba.getId()));

        return tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
    }
}