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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for WABA Account lifecycle management
 *
 * Responsibilities:
 * - Create WABA after Meta embedded signup
 * - Read/list WABAs per organization
 * - Update WABA status
 * - Sync phone numbers from Meta
 * - Disconnect WABA from platform
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

    /**
     * Create WABA account after successful Meta embedded signup
     *
     * Flow:
     * 1. Guard duplicate WABA
     * 2. Save/update Meta OAuth token
     * 3. Save WABA record
     * 4. Sync phone numbers from Meta (best-effort, won't fail the request)
     */
    @Transactional
    public WabaResponse createWabaAccount(CreateWabaRequest request) {
        log.info("Creating WABA: organizationId={}, wabaId={}", request.getOrganizationId(), request.getWabaId());

        // Guard: duplicate WABA for same org
        if (wabaRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), request.getWabaId())) {
            throw DuplicateWabaException.forOrganization(request.getOrganizationId(), request.getWabaId());
        }

        // Save or refresh the OAuth token
        MetaOAuthAccount oauthAccount = saveOrUpdateOAuthAccount(request);

        // Create the WABA record
        WabaAccount wabaAccount = WabaAccount.builder()
                .organizationId(request.getOrganizationId())
                .metaOAuthAccountId(oauthAccount.getId())
                .wabaId(request.getWabaId())
                .status(WabaStatus.ACTIVE)
                .build();

        wabaAccount = wabaRepository.save(wabaAccount);
        log.info("WABA saved: id={}", wabaAccount.getId());

        // Sync phone numbers from Meta — best effort (don't fail if Meta is temporarily down)
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(wabaAccount, oauthAccount.getAccessToken());
        } catch (Exception ex) {
            log.warn("Phone number sync skipped during WABA creation (will auto-retry on next sync). Error: {}",
                    ex.getMessage());
        }

        return WabaMapper.toWabaResponseWithPhones(
                wabaRepository.findByIdWithPhoneNumbers(wabaAccount.getId()).orElse(wabaAccount)
        );
    }

    // ========================
    // READ
    // ========================

    /**
     * Get a single WABA with its phone numbers by database ID
     */
    @Transactional(readOnly = true)
    public WabaResponse getWabaById(Long wabaId) {
        log.debug("Fetching WABA by id: {}", wabaId);
        return wabaRepository.findByIdWithPhoneNumbers(wabaId)
                .map(WabaMapper::toWabaResponseWithPhones)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));
    }

    /**
     * Get all WABAs for an organization — paginated, without phone numbers
     * Use this for lists/tables in UI
     */
    @Transactional(readOnly = true)
    public Page<WabaResponse> getWabasByOrganization(Long organizationId, Pageable pageable) {
        log.debug("Fetching WABAs for organizationId={}", organizationId);
        return wabaRepository.findByOrganizationId(organizationId, pageable)
                .map(WabaMapper::toWabaResponse);
    }

    /**
     * Get all WABAs for an organization with their phone numbers
     * Use this for dropdowns, project assignment screens
     */
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

    /**
     * Update WABA status (active / suspended / disconnected)
     * Called by admin or triggered by Meta webhook events
     */
    @Transactional
    public WabaResponse updateWabaStatus(Long wabaId, UpdateWabaStatusRequest request) {
        log.info("Updating WABA status: wabaId={}, newStatus={}", wabaId, request.getStatus());

        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        WabaStatus oldStatus = waba.getStatus();
        WabaStatus newStatus = WabaStatus.fromValue(request.getStatus());

        waba.setStatus(newStatus);
        waba = wabaRepository.save(waba);

        log.info("WABA status changed: wabaId={}, {} → {}", wabaId, oldStatus, newStatus);
        return WabaMapper.toWabaResponse(waba);
    }

    /**
     * Manually trigger phone number sync from Meta
     * Use this when DB may be out of sync (e.g. after downtime during WABA creation)
     */
    @Transactional
    public WabaResponse syncPhoneNumbers(Long wabaId) {
        log.info("Manual phone sync triggered for wabaId={}", wabaId);

        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException("OAuth account not found for WABA: " + wabaId));

        phoneNumberService.syncPhoneNumbersFromMeta(waba, oauthAccount.getAccessToken());

        return WabaMapper.toWabaResponseWithPhones(
                wabaRepository.findByIdWithPhoneNumbers(wabaId)
                        .orElseThrow(() -> WabaNotFoundException.withId(wabaId))
        );
    }

    // ========================
    // DELETE / DISCONNECT
    // ========================

    /**
     * Disconnect WABA from the platform
     * Sets status to DISCONNECTED — does NOT delete from Meta
     */
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
     * Save or update Meta OAuth account for a given Business Manager ID.
     * If the same BM was connected before, updates the token.
     * Otherwise creates a new OAuth account record.
     */
    private MetaOAuthAccount saveOrUpdateOAuthAccount(CreateWabaRequest request) {
        return metaOAuthRepository
                .findByOrganizationIdAndBusinessManagerId(
                        request.getOrganizationId(),
                        request.getBusinessManagerId())
                .map(existing -> {
                    existing.setAccessToken(request.getAccessToken());
                    if (request.getExpiresIn() != null) {
                        existing.setExpiresAt(LocalDateTime.now().plusSeconds(request.getExpiresIn()));
                    }
                    log.info("OAuth token refreshed for businessManagerId={}", request.getBusinessManagerId());
                    return metaOAuthRepository.save(existing);
                })
                .orElseGet(() -> {
                    MetaOAuthAccount newAccount = MetaOAuthAccount.builder()
                            .organizationId(request.getOrganizationId())
                            .businessManagerId(request.getBusinessManagerId())
                            .accessToken(request.getAccessToken())
                            .expiresAt(request.getExpiresIn() != null
                                    ? LocalDateTime.now().plusSeconds(request.getExpiresIn())
                                    : null)
                            .build();
                    log.info("New OAuth account created for businessManagerId={}", request.getBusinessManagerId());
                    return metaOAuthRepository.save(newAccount);
                });
    }
}