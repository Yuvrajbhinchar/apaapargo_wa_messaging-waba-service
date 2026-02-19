package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.dto.request.CreateWabaRequest;
import com.aigreentick.services.wabaaccounts.dto.request.UpdateWabaStatusRequest;
import com.aigreentick.services.wabaaccounts.dto.response.WabaResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.DuplicateWabaException;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.mapper.WabaMapper;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for WABA Account lifecycle management.
 *
 * ═══════════════════════════════════════════════════════════════════
 * BLOCKER 2 FIX: syncPhoneNumbers() was sending encrypted token to Meta
 * ═══════════════════════════════════════════════════════════════════
 *
 * oauthAccount.getAccessToken() returns "ENC:..." (encrypted at rest).
 * This service previously passed that directly to Meta → 401 every time.
 *
 * FIX: Injected TokenEncryptionService, decrypt before all Meta API calls.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ADDITIONAL FIX: saveOrUpdateOAuthAccount() now ENCRYPTS tokens
 * ═══════════════════════════════════════════════════════════════════
 *
 * The direct REST endpoint POST /api/v1/waba-accounts called
 * saveOrUpdateOAuthAccount() which stored request.getAccessToken() in
 * PLAINTEXT. EmbeddedSignupService correctly encrypts, but this path
 * did not. Now both paths encrypt consistently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WabaService {

    private final WabaAccountRepository wabaRepository;
    private final MetaOAuthAccountRepository metaOAuthRepository;
    private final MetaApiClient metaApiClient;
    private final PhoneNumberService phoneNumberService;
    private final TokenEncryptionService tokenEncryptionService;  // ← BLOCKER 2 FIX: added

    // ========================
    // CREATE
    // ========================

    @Transactional
    public WabaResponse createWabaAccount(CreateWabaRequest request) {
        log.info("Creating WABA: organizationId={}, wabaId={}", request.getOrganizationId(), request.getWabaId());

        // App-level check (fast path)
        if (wabaRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), request.getWabaId())) {
            throw DuplicateWabaException.forOrganization(request.getOrganizationId(), request.getWabaId());
        }

        MetaOAuthAccount oauthAccount = saveOrUpdateOAuthAccount(request);

        // FIX: Catch race condition at DB level
        WabaAccount wabaAccount;
        try {
            wabaAccount = WabaAccount.builder()
                    .organizationId(request.getOrganizationId())
                    .metaOAuthAccountId(oauthAccount.getId())
                    .wabaId(request.getWabaId())
                    .status(WabaStatus.ACTIVE)
                    .build();
            wabaAccount = wabaRepository.save(wabaAccount);
            log.info("WABA saved: id={}", wabaAccount.getId());
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on WABA insert via createWabaAccount: wabaId={}", request.getWabaId());
            throw new DuplicateWabaException(
                    "This WABA was already connected by a concurrent request. Please check your WABA list.");
        }

        try {
            // FIX: decrypt token before passing to Meta API
            String decryptedToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
            phoneNumberService.syncPhoneNumbersFromMeta(wabaAccount, decryptedToken);
        } catch (Exception ex) {
            log.warn("Phone number sync skipped during WABA creation. Error: {}", ex.getMessage());
        }

        return WabaMapper.toWabaResponseWithPhones(
                wabaRepository.findByIdWithPhoneNumbers(wabaAccount.getId()).orElse(wabaAccount)
        );
    }

    // ========================
    // READ
    // ========================

    @Transactional(readOnly = true)
    public WabaResponse getWabaById(Long wabaId) {
        log.debug("Fetching WABA by id: {}", wabaId);
        return wabaRepository.findByIdWithPhoneNumbers(wabaId)
                .map(WabaMapper::toWabaResponseWithPhones)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
    }

    @Transactional(readOnly = true)
    public Page<WabaResponse> getWabasByOrganization(Long organizationId, Pageable pageable) {
        log.debug("Fetching WABAs for organizationId={}", organizationId);
        return wabaRepository.findByOrganizationId(organizationId, pageable)
                .map(WabaMapper::toWabaResponse);
    }

    @Transactional(readOnly = true)
    public List<WabaResponse> getWabasWithPhonesByOrganization(Long organizationId) {
        log.debug("Fetching WABAs with phones for organizationId={}", organizationId);
        return wabaRepository.findByOrganizationIdWithPhoneNumbers(organizationId)
                .stream()
                .map(WabaMapper::toWabaResponseWithPhones)
                .toList();
    }

    // ========================
    // UPDATE
    // ========================

    @Transactional
    public WabaResponse updateWabaStatus(Long wabaId, UpdateWabaStatusRequest request) {
        log.info("Updating WABA status: wabaId={}, newStatus={}", wabaId, request.getStatus());
        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
        WabaStatus oldStatus = waba.getStatus();
        waba.setStatus(WabaStatus.fromValue(request.getStatus()));
        waba = wabaRepository.save(waba);
        log.info("WABA status changed: wabaId={}, {} → {}", wabaId, oldStatus, waba.getStatus());
        return WabaMapper.toWabaResponse(waba);
    }

    /**
     * Manual phone sync triggered via REST endpoint.
     *
     * ═══ BLOCKER 2 FIX ═══
     * OLD BROKEN:
     *   phoneNumberService.syncPhoneNumbersFromMeta(waba, oauthAccount.getAccessToken());
     *   → oauthAccount.getAccessToken() returns "ENC:..." → Meta gets 401
     *
     * NEW FIXED:
     *   String decryptedToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
     *   phoneNumberService.syncPhoneNumbersFromMeta(waba, decryptedToken);
     */
    @Transactional
    public WabaResponse syncPhoneNumbers(Long wabaId) {
        log.info("Manual phone sync triggered for wabaId={}", wabaId);
        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA: " + wabaId));

        // ═══ BLOCKER 2 FIX: decrypt before use ═══
        String decryptedToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
        phoneNumberService.syncPhoneNumbersFromMeta(waba, decryptedToken);

        return WabaMapper.toWabaResponseWithPhones(
                wabaRepository.findByIdWithPhoneNumbers(wabaId)
                        .orElseThrow(() -> WabaNotFoundException.withId(wabaId))
        );
    }

    // ========================
    // DELETE / DISCONNECT
    // ========================

    @Transactional
    public void disconnectWaba(Long wabaId) {
        log.info("Disconnecting WABA: wabaId={}", wabaId);
        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
        waba.disconnect();
        wabaRepository.save(waba);
        log.info("WABA disconnected: wabaId={}", wabaId);
    }

    // ========================
    // PRIVATE HELPERS
    // ========================

    /**
     * Save or update OAuth account for an organization.
     *
     * ═══ ADDITIONAL FIX: Token encryption on the direct REST path ═══
     *
     * The embedded signup flow (EmbeddedSignupService) encrypts tokens
     * before saving. But the direct REST endpoint POST /api/v1/waba-accounts
     * calls this method, which previously stored the access token in PLAINTEXT.
     *
     * OLD BROKEN:
     *   existing.setAccessToken(request.getAccessToken());  // plaintext!
     *
     * NEW FIXED:
     *   existing.setAccessToken(tokenEncryptionService.encrypt(request.getAccessToken()));
     */
    private MetaOAuthAccount saveOrUpdateOAuthAccount(CreateWabaRequest request) {
        return metaOAuthRepository
                .findByOrganizationId(request.getOrganizationId())
                .map(existing -> {
                    // ═══ FIX: encrypt token before saving ═══
                    existing.setAccessToken(tokenEncryptionService.encrypt(request.getAccessToken()));
                    if (request.getExpiresIn() != null) {
                        existing.setExpiresAt(LocalDateTime.now().plusSeconds(request.getExpiresIn()));
                    }
                    log.info("OAuth token refreshed for orgId={}", request.getOrganizationId());
                    return metaOAuthRepository.save(existing);
                })
                .orElseGet(() -> {
                    // ═══ FIX: encrypt token before saving ═══
                    MetaOAuthAccount newAccount = MetaOAuthAccount.builder()
                            .organizationId(request.getOrganizationId())
                            .accessToken(tokenEncryptionService.encrypt(request.getAccessToken()))
                            .expiresAt(request.getExpiresIn() != null
                                    ? LocalDateTime.now().plusSeconds(request.getExpiresIn())
                                    : null)
                            .build();
                    log.info("New OAuth account created for orgId={}", request.getOrganizationId());
                    return metaOAuthRepository.save(newAccount);
                });
    }
}