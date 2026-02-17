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
 * Service for WABA Account management
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
     */
    @Transactional
    public WabaResponse createWabaAccount(CreateWabaRequest request) {
        log.info("Creating WABA account for organizationId: {}, wabaId: {}",
                request.getOrganizationId(), request.getWabaId());

        // 1. Check for duplicates
        if (wabaRepository.existsByOrganizationIdAndWabaId(
                request.getOrganizationId(), request.getWabaId())) {
            throw DuplicateWabaException.forOrganization(
                    request.getOrganizationId(), request.getWabaId());
        }

        // 2. Save or update Meta OAuth account
        MetaOAuthAccount oauthAccount = saveOrUpdateOAuthAccount(request);

        // 3. Create and save WABA account
        WabaAccount wabaAccount = WabaAccount.builder()
                .organizationId(request.getOrganizationId())
                .metaOAuthAccountId(oauthAccount.getId())
                .wabaId(request.getWabaId())
                .status(WabaStatus.ACTIVE)
                .build();

        wabaAccount = wabaRepository.save(wabaAccount);
        log.info("WABA account saved with ID: {}", wabaAccount.getId());

        // 4. Sync phone numbers from Meta (best effort - don't fail if Meta is down)
        try {
            phoneNumberService.syncPhoneNumbersFromMeta(wabaAccount, oauthAccount.getAccessToken());
        } catch (Exception ex) {
            log.warn("Could not sync phone numbers during WABA creation. Will retry later. Error: {}",
                    ex.getMessage());
        }

        return WabaMapper.toWabaResponseWithPhones(
                wabaRepository.findByIdWithPhoneNumbers(wabaAccount.getId())
                        .orElse(wabaAccount)
        );
    }

    // ========================
    // READ
    // ========================

    /**
     * Get WABA by database ID
     */
    @Transactional(readOnly = true)
    public WabaResponse getWabaById(Long wabaId) {
        log.debug("Fetching WABA by ID: {}", wabaId);

        WabaAccount waba = wabaRepository.findByIdWithPhoneNumbers(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        return WabaMapper.toWabaResponseWithPhones(waba);
    }

    /**
     * Get all WABAs for an organization (paginated)
     */
    @Transactional(readOnly = true)
    public Page<WabaResponse> getWabasByOrganization(Long organizationId, Pageable pageable) {
        log.debug("Fetching WABAs for organizationId: {}", organizationId);

        return wabaRepository.findByOrganizationId(organizationId, pageable)
                .map(WabaMapper::toWabaResponse);
    }

    /**
     * Get all WABAs for an organization with phone numbers
     */
    @Transactional(readOnly = true)
    public List<WabaResponse> getWabasWithPhonesByOrganization(Long organizationId) {
        log.debug("Fetching WABAs with phones for organizationId: {}", organizationId);

        return wabaRepository.findByOrganizationIdWithPhoneNumbers(organizationId)
                .stream()
                .map(WabaMapper::toWabaResponseWithPhones)
                .toList();
    }

    // ========================
    // UPDATE
    // ========================

    /**
     * Update WABA status
     */
    @Transactional
    public WabaResponse updateWabaStatus(Long wabaId, UpdateWabaStatusRequest request) {
        log.info("Updating WABA status: wabaId={}, newStatus={}", wabaId, request.getStatus());

        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        WabaStatus newStatus = WabaStatus.fromValue(request.getStatus());
        WabaStatus oldStatus = waba.getStatus();

        waba.setStatus(newStatus);
        waba = wabaRepository.save(waba);

        log.info("WABA status updated: wabaId={}, {} -> {}", wabaId, oldStatus, newStatus);

        return WabaMapper.toWabaResponse(waba);
    }

    /**
     * Sync WABA phone numbers from Meta manually
     */
    @Transactional
    public WabaResponse syncPhoneNumbers(Long wabaId) {
        log.info("Manual sync triggered for wabaId: {}", wabaId);

        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        MetaOAuthAccount oauthAccount = metaOAuthRepository
                .findById(waba.getMetaOAuthAccountId())
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
     * Disconnect WABA from platform
     */
    @Transactional
    public void disconnectWaba(Long wabaId) {
        log.info("Disconnecting WABA: {}", wabaId);

        WabaAccount waba = wabaRepository.findById(wabaId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaId));

        waba.disconnect();
        wabaRepository.save(waba);

        log.info("WABA disconnected: {}", wabaId);
    }

    // ========================
    // PRIVATE HELPERS
    // ========================

    private MetaOAuthAccount saveOrUpdateOAuthAccount(CreateWabaRequest request) {
        // Check if this business manager already has an OAuth account for this org
        return metaOAuthRepository
                .findByOrganizationIdAndBusinessManagerId(
                        request.getOrganizationId(),
                        request.getBusinessManagerId()
                )
                .map(existing -> {
                    // Update existing token
                    existing.setAccessToken(request.getAccessToken());
                    if (request.getExpiresIn() != null) {
                        existing.setExpiresAt(LocalDateTime.now()
                                .plusSeconds(request.getExpiresIn()));
                    }
                    log.info("Updated OAuth account for businessManagerId: {}",
                            request.getBusinessManagerId());
                    return metaOAuthRepository.save(existing);
                })
                .orElseGet(() -> {
                    // Create new OAuth account
                    MetaOAuthAccount newAccount = MetaOAuthAccount.builder()
                            .organizationId(request.getOrganizationId())
                            .businessManagerId(request.getBusinessManagerId())
                            .accessToken(request.getAccessToken())
                            .expiresAt(request.getExpiresIn() != null
                                    ? LocalDateTime.now().plusSeconds(request.getExpiresIn())
                                    : null)
                            .build();
                    log.info("Created new OAuth account for businessManagerId: {}",
                            request.getBusinessManagerId());
                    return metaOAuthRepository.save(newAccount);
                });
    }
}