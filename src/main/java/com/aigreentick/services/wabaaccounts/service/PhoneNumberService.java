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
import com.aigreentick.services.wabaaccounts.service.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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
    // REGISTER & VERIFY
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public PhoneNumberResponse registerPhoneNumber(RegisterPhoneNumberRequest request) {
        log.info("Registering phone number: phoneNumberId={}, wabaAccountId={}",
                request.getPhoneNumberId(), request.getWabaAccountId());

        WabaAccount waba = wabaRepository.findById(request.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(request.getWabaAccountId()));

        if (!waba.isActive()) {
            throw InvalidRequestException.wabaNotActive(waba.getId());
        }

        long currentCount = phoneNumberRepository.countByWabaAccountId(waba.getId());
        if (currentCount >= WabaConstants.MAX_PHONE_NUMBERS_PER_WABA) {
            throw InvalidRequestException.maxPhoneNumbersReached(WabaConstants.MAX_PHONE_NUMBERS_PER_WABA);
        }

        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA: " + waba.getId()));

        String decryptedToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());

        var metaResponse = metaApiClient.registerPhoneNumber(
                request.getPhoneNumberId(), decryptedToken, request.getPin());

        if (!metaResponse.isOk()) {
            throw new InvalidRequestException(
                    "Meta rejected phone registration: " + metaResponse.getErrorMessage());
        }

        WabaPhoneNumber phoneNumber = WabaPhoneNumber.builder()
                .wabaAccountId(waba.getId())
                .phoneNumberId(request.getPhoneNumberId())
                .status(PhoneNumberStatus.ACTIVE)
                .qualityRating(QualityRating.UNKNOWN)
                .messagingLimitTier(MessagingLimitTier.TIER_1K)
                .build();

        phoneNumber = phoneNumberRepository.save(phoneNumber);
        log.info("Phone number registered: phoneNumberId={}", request.getPhoneNumberId());
        return WabaMapper.toPhoneNumberResponse(phoneNumber);
    }


    @Transactional
    public void requestVerificationCode(RequestVerificationCodeRequest request) {
        log.info("Requesting verification code: phoneNumberId={}, method={}",
                request.getPhoneNumberId(), request.getMethod());

        // FIX 6: was findById(String) → ClassCastException (expects Long)
        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = resolveAccessToken(phone);

        var metaResponse = metaApiClient.requestVerificationCode(
                phone.getPhoneNumberId(), accessToken,
                request.getMethod(), request.getLocale());

        if (!metaResponse.isOk()) {
            throw new InvalidRequestException(
                    "Meta rejected verification code request: " + metaResponse.getErrorMessage());
        }
        log.info("Verification code sent to: {}", phone.getDisplayPhoneNumber());
    }

    /**
     * findByPhoneNumberId instead of findById
     */
    @Transactional
    public PhoneNumberResponse verifyPhoneNumber(VerifyPhoneNumberRequest request) {
        log.info("Verifying phone number: phoneNumberId={}", request.getPhoneNumberId());

        // FIX 6: was findById(String) → ClassCastException (expects Long)
        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = resolveAccessToken(phone);

        var metaResponse = metaApiClient.verifyCode(
                phone.getPhoneNumberId(), accessToken, request.getCode());

        if (!metaResponse.isOk()) {
            throw new InvalidRequestException("Verification failed: " + metaResponse.getErrorMessage());
        }

        phone.setStatus(PhoneNumberStatus.ACTIVE);
        phone = phoneNumberRepository.save(phone);
        log.info("Phone number verified: {}", phone.getDisplayPhoneNumber());
        return WabaMapper.toPhoneNumberResponse(phone);
    }

    // ═══════════════════════════════════════════════════════════
    // SYNC FROM META
    // ═══════════════════════════════════════════════════════════

    @Transactional
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

        int synced = 0;
        for (Map<String, Object> phoneData : phonesData) {
            try {
                upsertPhone(waba.getId(), phoneData);
                synced++;
            } catch (Exception ex) {
                log.error("Failed to sync phone: data={}, error={}", phoneData, ex.getMessage());
            }
        }
        log.info("Phone sync: {}/{} synced for wabaId={}", synced, phonesData.size(), waba.getWabaId());
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private void upsertPhone(Long wabaAccountId, Map<String, Object> data) {
        String metaPhoneId = String.valueOf(data.get("id"));

        phoneNumberRepository.findByPhoneNumberId(metaPhoneId).ifPresentOrElse(
                existing -> {
                    applyMetaData(existing, data);
                    phoneNumberRepository.save(existing);
                    log.debug("Updated phone: {}", metaPhoneId);
                },
                () -> {
                    WabaPhoneNumber newPhone = WabaPhoneNumber.builder()
                            .wabaAccountId(wabaAccountId)
                            .phoneNumberId(metaPhoneId)
                            .displayPhoneNumber(str(data, "display_phone_number"))
                            .verifiedName(str(data, "verified_name"))
                            .status(parseStatus(data))
                            .qualityRating(parseQuality(data))
                            .messagingLimitTier(parseTier(data))
                            .build();
                    phoneNumberRepository.save(newPhone);
                    log.debug("Inserted phone: {}", metaPhoneId);
                }
        );
    }

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
        try { return PhoneNumberStatus.fromValue(str(data, "status").toLowerCase()); }
        catch (Exception e) { return PhoneNumberStatus.ACTIVE; }
    }

    private QualityRating parseQuality(Map<String, Object> data) {
        try { return QualityRating.valueOf(str(data, "quality_rating").toUpperCase()); }
        catch (Exception e) { return QualityRating.UNKNOWN; }
    }

    private MessagingLimitTier parseTier(Map<String, Object> data) {
        try { return MessagingLimitTier.valueOf(str(data, "messaging_limit_tier").toUpperCase()); }
        catch (Exception e) { return MessagingLimitTier.TIER_1K; }
    }

    private String str(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    private String resolveAccessToken(WabaPhoneNumber phone) {
        WabaAccount waba = wabaRepository.findById(phone.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(phone.getWabaAccountId()));

        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA: " + waba.getId()));

        return tokenEncryptionService.decrypt(oauthAccount.getAccessToken());
    }
}