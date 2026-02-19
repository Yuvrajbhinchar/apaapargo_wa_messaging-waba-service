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
 * ─── Senior Review Fix (BLOCKER 2) ────────────────────────────────────────
 * saveOrUpdateOAuthAccount() now looks up by organizationId only.
 * Removed the businessManagerId lookup — the token belongs to the org, not the BM.
 *
 * ─── Senior Review Fix (BLOCKER 1 — Race Condition) ───────────────────────
 * createWabaAccount() now catches DataIntegrityViolationException on save
 * and maps it to DuplicateWabaException with a friendly message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WabaService {

    private final WabaAccountRepository wabaRepository;
    private final MetaOAuthAccountRepository metaOAuthRepository;
    private final MetaApiClient metaApiClient;
    private final PhoneNumberService phoneNumberService;

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
            phoneNumberService.syncPhoneNumbersFromMeta(wabaAccount, oauthAccount.getAccessToken());
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

    @Transactional
    public WabaResponse syncPhoneNumbers(Long wabaId) {
        log.info("Manual phone sync triggered for wabaId={}", wabaId);
        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA: " + wabaId));
        phoneNumberService.syncPhoneNumbersFromMeta(waba, oauthAccount.getAccessToken());
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
     * FIX (BLOCKER 2): Look up OAuth account by organizationId only.
     *
     * Old: findByOrganizationIdAndBusinessManagerId(orgId, bmId)
     *   → Created a new row for each Business Manager the user had
     *   → Same user connecting 2nd WABA from different BM = 2 OAuth rows
     *
     * New: findByOrganizationId(orgId)
     *   → One token per org. If org already has a token, we refresh it.
     *   → All WABAs under the org share the same OAuth account.
     */
    private MetaOAuthAccount saveOrUpdateOAuthAccount(CreateWabaRequest request) {
        return metaOAuthRepository
                .findByOrganizationId(request.getOrganizationId())
                .map(existing -> {
                    existing.setAccessToken(request.getAccessToken());
                    if (request.getExpiresIn() != null) {
                        existing.setExpiresAt(LocalDateTime.now().plusSeconds(request.getExpiresIn()));
                    }
                    log.info("OAuth token refreshed for orgId={}", request.getOrganizationId());
                    return metaOAuthRepository.save(existing);
                })
                .orElseGet(() -> {
                    MetaOAuthAccount newAccount = MetaOAuthAccount.builder()
                            .organizationId(request.getOrganizationId())
                            .accessToken(request.getAccessToken())
                            .expiresAt(request.getExpiresIn() != null
                                    ? LocalDateTime.now().plusSeconds(request.getExpiresIn())
                                    : null)
                            .build();
                    log.info("New OAuth account created for orgId={}", request.getOrganizationId());
                    return metaOAuthRepository.save(newAccount);
                });
    }
}