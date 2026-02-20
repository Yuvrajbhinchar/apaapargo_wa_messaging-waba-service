package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.exception.DuplicateWabaException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.security.TokenEncryptionService;
import com.aigreentick.services.wabaaccounts.service.OnboardingModel.PersistedOnboardingData;
import com.aigreentick.services.wabaaccounts.service.OnboardingModel.TokenResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class OnboardingPersistenceService {

    private final MetaOAuthAccountRepository oauthAccountRepository;
    private final WabaAccountRepository wabaAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;

    @Transactional
    public PersistedOnboardingData persistOnboardingData(
            EmbeddedSignupCallbackRequest request,
            TokenResult tokenResult,
            String wabaId,
            String businessManagerId
    ) {
        if (wabaAccountRepository.existsByWabaId(wabaId)) {
            if (wabaAccountRepository.existsByOrganizationIdAndWabaId(request.getOrganizationId(), wabaId)) {
                throw DuplicateWabaException.forOrganization(request.getOrganizationId(), wabaId);
            }
            log.error("Cross-tenant WABA conflict: wabaId={} requested by orgId={}",
                    wabaId, request.getOrganizationId());
            throw new DuplicateWabaException(
                    "This WhatsApp Business Account is already connected to another account.");
        }

        String encryptedToken = tokenEncryptionService.encrypt(tokenResult.accessToken());
        MetaOAuthAccount oauthAccount = saveOAuthAccount(
                request.getOrganizationId(), encryptedToken, tokenResult.expiresIn());

        WabaAccount waba;
        try {
            waba = WabaAccount.builder()
                    .organizationId(request.getOrganizationId())
                    .metaOAuthAccountId(oauthAccount.getId())
                    .wabaId(wabaId)
                    .businessManagerId(businessManagerId)
                    .status(WabaStatus.ACTIVE)
                    .build();
            waba = wabaAccountRepository.save(waba);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on WABA insert: wabaId={}", wabaId);
            throw new DuplicateWabaException("This WABA was just connected by another request.");
        }

        log.info("Onboarding data persisted: wabaAccountId={}, orgId={}, businessManagerId={}",
                waba.getId(), request.getOrganizationId(), businessManagerId);

        return new PersistedOnboardingData(waba, oauthAccount);
    }

    // ── moved from EmbeddedSignupService ──────────────────────────────────────
    private MetaOAuthAccount saveOAuthAccount(Long organizationId,
                                              String encryptedToken,
                                              long expiresInSeconds) {
        return oauthAccountRepository.findByOrganizationId(organizationId)
                .map(existing -> {
                    existing.setAccessToken(encryptedToken);
                    existing.setExpiresAt(expiresInSeconds > 0
                            ? LocalDateTime.now().plusSeconds(expiresInSeconds) : null);
                    return oauthAccountRepository.save(existing);
                })
                .orElseGet(() -> oauthAccountRepository.save(
                        MetaOAuthAccount.builder()
                                .organizationId(organizationId)
                                .accessToken(encryptedToken)
                                .expiresAt(expiresInSeconds > 0
                                        ? LocalDateTime.now().plusSeconds(expiresInSeconds) : null)
                                .build()));
    }
}