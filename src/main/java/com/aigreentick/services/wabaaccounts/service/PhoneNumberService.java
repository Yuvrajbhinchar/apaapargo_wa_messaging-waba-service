package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.client.MetaApiClient;
import com.aigreentick.services.wabaaccounts.constants.MessagingLimitTier;
import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.request.RegisterPhoneNumberRequest;
import com.aigreentick.services.wabaaccounts.dto.request.RequestVerificationCodeRequest;
import com.aigreentick.services.wabaaccounts.dto.request.VerifyPhoneNumberRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Service for Phone Number management
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneNumberService {

    private final WabaPhoneNumberRepository phoneNumberRepository;
    private final WabaAccountRepository wabaRepository;
    private final MetaOAuthAccountRepository metaOAuthRepository;
    private final MetaApiClient metaApiClient;

    // ========================
    // READ
    // ========================

    /**
     * Get all phone numbers for a WABA
     */
    @Transactional(readOnly = true)
    public List<PhoneNumberResponse> getPhoneNumbersByWaba(Long wabaAccountId) {
        log.debug("Fetching phone numbers for wabaAccountId: {}", wabaAccountId);

        if (!wabaRepository.existsById(wabaAccountId)) {
            throw WabaNotFoundException.withId(wabaAccountId);
        }

        return phoneNumberRepository.findByWabaAccountId(wabaAccountId)
                .stream()
                .map(WabaMapper::toPhoneNumberResponse)
                .toList();
    }

    /**
     * Get single phone number by ID
     */
    @Transactional(readOnly = true)
    public PhoneNumberResponse getPhoneNumberById(Long phoneNumberId) {
        WabaPhoneNumber phone = phoneNumberRepository.findById(phoneNumberId)
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(phoneNumberId));

        return WabaMapper.toPhoneNumberResponse(phone);
    }

    // ========================
    // REGISTER & VERIFY
    // ========================

    /**
     * Request verification code to verify phone number (SMS or Voice)
     */
    @Transactional
    public void requestVerificationCode(RequestVerificationCodeRequest request) {
        log.info("Requesting verification code for phoneNumberId: {}, method: {}",
                request.getPhoneNumberId(), request.getMethod());

        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = getAccessTokenForPhone(phone);

        metaApiClient.requestVerificationCode(
                phone.getPhoneNumberId(),
                accessToken,
                request.getMethod(),
                request.getLocale()
        );

        log.info("Verification code sent for phone: {}", phone.getDisplayPhoneNumber());
    }

    /**
     * Verify phone number with received OTP
     */
    @Transactional
    public PhoneNumberResponse verifyPhoneNumber(VerifyPhoneNumberRequest request) {
        log.info("Verifying phone number: {}", request.getPhoneNumberId());

        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = getAccessTokenForPhone(phone);

        // Call Meta to verify
        metaApiClient.verifyCode(phone.getPhoneNumberId(), accessToken, request.getCode());

        // Update status to active after successful verification
        phone.setStatus(PhoneNumberStatus.ACTIVE);
        phone = phoneNumberRepository.save(phone);

        log.info("Phone number verified successfully: {}", phone.getDisplayPhoneNumber());
        return WabaMapper.toPhoneNumberResponse(phone);
    }

    /**
     * Register phone number with Meta (enable messaging)
     */
    @Transactional
    public PhoneNumberResponse registerPhoneNumber(RegisterPhoneNumberRequest request) {
        log.info("Registering phone number for wabaAccountId: {}", request.getWabaAccountId());

        WabaAccount waba = wabaRepository.findById(request.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(request.getWabaAccountId()));

        if (!waba.isActive()) {
            throw InvalidRequestException.wabaNotActive(waba.getId());
        }

        // Check phone limit per WABA
        long currentPhoneCount = phoneNumberRepository.countByWabaAccountId(waba.getId());
        if (currentPhoneCount >= WabaConstants.MAX_PHONE_NUMBERS_PER_WABA) {
            throw InvalidRequestException.maxPhoneNumbersReached(WabaConstants.MAX_PHONE_NUMBERS_PER_WABA);
        }

        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException("OAuth account not found"));

        // Call Meta API to register phone number
        metaApiClient.registerPhoneNumber(
                request.getPhoneNumberId(),
                oauthAccount.getAccessToken(),
                request.getPin()
        );

        // Save phone number to DB
        WabaPhoneNumber phoneNumber = WabaPhoneNumber.builder()
                .wabaAccountId(waba.getId())
                .phoneNumberId(request.getPhoneNumberId())
                .status(PhoneNumberStatus.ACTIVE)
                .build();

        phoneNumber = phoneNumberRepository.save(phoneNumber);

        log.info("Phone number registered: {}", request.getPhoneNumberId());
        return WabaMapper.toPhoneNumberResponse(phoneNumber);
    }

    // ========================
    // SYNC FROM META
    // ========================

    /**
     * Sync phone numbers from Meta API into database
     */
    @Transactional
    public void syncPhoneNumbersFromMeta(WabaAccount waba, String accessToken) {
        log.info("Syncing phone numbers from Meta for wabaId: {}", waba.getWabaId());

        var response = metaApiClient.getPhoneNumbers(waba.getWabaId(), accessToken);

        if (!Boolean.TRUE.equals(response.getSuccess())) {
            log.warn("Meta API returned error during phone sync for waba: {}", waba.getWabaId());
            return;
        }

        List<Map<String, Object>> phonesData =
                (List<Map<String, Object>>) response.getData().get("data");

        if (phonesData == null || phonesData.isEmpty()) {
            log.info("No phone numbers found in Meta for waba: {}", waba.getWabaId());
            return;
        }

        int synced = 0;
        for (Map<String, Object> phoneData : phonesData) {
            try {
                syncSinglePhone(waba.getId(), phoneData);
                synced++;
            } catch (Exception ex) {
                log.error("Failed to sync phone number: {}", phoneData, ex);
            }
        }

        log.info("Synced {}/{} phone numbers for waba: {}", synced, phonesData.size(), waba.getWabaId());
    }

    // ========================
    // PRIVATE HELPERS
    // ========================

    private void syncSinglePhone(Long wabaAccountId, Map<String, Object> phoneData) {
        String metaPhoneNumberId = String.valueOf(phoneData.get("id"));

        phoneNumberRepository.findByPhoneNumberId(metaPhoneNumberId)
                .ifPresentOrElse(
                        existing -> {
                            // Update existing phone
                            updatePhoneFromMetaData(existing, phoneData);
                            phoneNumberRepository.save(existing);
                            log.debug("Updated phone number: {}", metaPhoneNumberId);
                        },
                        () -> {
                            // Insert new phone
                            WabaPhoneNumber newPhone = buildPhoneFromMetaData(wabaAccountId, phoneData);
                            phoneNumberRepository.save(newPhone);
                            log.debug("Inserted new phone number: {}", metaPhoneNumberId);
                        }
                );
    }

    private WabaPhoneNumber buildPhoneFromMetaData(Long wabaAccountId, Map<String, Object> data) {
        return WabaPhoneNumber.builder()
                .wabaAccountId(wabaAccountId)
                .phoneNumberId(String.valueOf(data.get("id")))
                .displayPhoneNumber(String.valueOf(data.getOrDefault("display_phone_number", "")))
                .verifiedName(String.valueOf(data.getOrDefault("verified_name", "")))
                .status(parsePhoneStatus(data))
                .qualityRating(parseQualityRating(data))
                .messagingLimitTier(parseMessagingTier(data))
                .build();
    }

    private void updatePhoneFromMetaData(WabaPhoneNumber phone, Map<String, Object> data) {
        phone.setDisplayPhoneNumber(String.valueOf(data.getOrDefault("display_phone_number",
                phone.getDisplayPhoneNumber())));
        phone.setVerifiedName(String.valueOf(data.getOrDefault("verified_name",
                phone.getVerifiedName())));
        phone.setStatus(parsePhoneStatus(data));
        phone.setQualityRating(parseQualityRating(data));
        phone.setMessagingLimitTier(parseMessagingTier(data));
    }

    private PhoneNumberStatus parsePhoneStatus(Map<String, Object> data) {
        try {
            String status = String.valueOf(data.getOrDefault("status", "ACTIVE")).toUpperCase();
            return PhoneNumberStatus.fromValue(status.toLowerCase());
        } catch (Exception e) {
            return PhoneNumberStatus.ACTIVE;
        }
    }

    private QualityRating parseQualityRating(Map<String, Object> data) {
        try {
            String rating = String.valueOf(data.getOrDefault("quality_rating", "UNKNOWN")).toUpperCase();
            return QualityRating.valueOf(rating);
        } catch (Exception e) {
            return QualityRating.UNKNOWN;
        }
    }

    private MessagingLimitTier parseMessagingTier(Map<String, Object> data) {
        try {
            String tier = String.valueOf(data.getOrDefault("messaging_limit_tier", "TIER_1K")).toUpperCase();
            return MessagingLimitTier.valueOf(tier);
        } catch (Exception e) {
            return MessagingLimitTier.TIER_1K;
        }
    }

    private String getAccessTokenForPhone(WabaPhoneNumber phone) {
        WabaAccount waba = wabaRepository.findById(phone.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(phone.getWabaAccountId()));

        return metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException("OAuth account not found"))
                .getAccessToken();
    }
}