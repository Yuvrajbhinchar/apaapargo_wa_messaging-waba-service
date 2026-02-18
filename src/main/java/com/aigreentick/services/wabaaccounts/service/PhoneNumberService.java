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
 * Service for phone number lifecycle management
 *
 * Responsibilities:
 * - Register phone numbers with Meta (Cloud API)
 * - Request and verify OTP codes
 * - Sync phone numbers from Meta into local DB
 * - List/get phone numbers per WABA
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
     * Get all phone numbers for a WABA account
     */
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

    /**
     * Get a single phone number by database ID
     */
    @Transactional(readOnly = true)
    public PhoneNumberResponse getPhoneNumberById(Long id) {
        log.debug("Fetching phone number by id={}", id);
        return phoneNumberRepository.findById(id)
                .map(WabaMapper::toPhoneNumberResponse)
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(id));
    }

    // ========================
    // REGISTER & VERIFY
    // ========================

    /**
     * Register a phone number with Meta to enable WhatsApp messaging
     *
     * Requires:
     * - WABA must be active
     * - Phone limit < 20
     * - User must have set up 2-step verification PIN on their WhatsApp
     */
    @Transactional
    public PhoneNumberResponse registerPhoneNumber(RegisterPhoneNumberRequest request) {
        log.info("Registering phone number: phoneNumberId={}, wabaAccountId={}",
                request.getPhoneNumberId(), request.getWabaAccountId());

        WabaAccount waba = wabaRepository.findById(request.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(request.getWabaAccountId()));

        // Guard: WABA must be active
        if (!waba.isActive()) {
            throw InvalidRequestException.wabaNotActive(waba.getId());
        }

        // Guard: phone number limit
        long currentCount = phoneNumberRepository.countByWabaAccountId(waba.getId());
        if (currentCount >= WabaConstants.MAX_PHONE_NUMBERS_PER_WABA) {
            throw InvalidRequestException.maxPhoneNumbersReached(WabaConstants.MAX_PHONE_NUMBERS_PER_WABA);
        }

        MetaOAuthAccount oauthAccount = metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException("OAuth account not found for WABA: " + waba.getId()));

        // Register with Meta Cloud API
        metaApiClient.registerPhoneNumber(
                request.getPhoneNumberId(),
                oauthAccount.getAccessToken(),
                request.getPin()
        );

        // Persist to database
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

    /**
     * Request OTP code sent to phone number via SMS or Voice call
     */
    @Transactional
    public void requestVerificationCode(RequestVerificationCodeRequest request) {
        log.info("Requesting verification code: phoneNumberId={}, method={}",
                request.getPhoneNumberId(), request.getMethod());

        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = resolveAccessToken(phone);

        metaApiClient.requestVerificationCode(
                phone.getPhoneNumberId(),
                accessToken,
                request.getMethod(),
                request.getLocale()
        );

        log.info("Verification code sent to: {}", phone.getDisplayPhoneNumber());
    }

    /**
     * Verify phone number with the OTP code received
     * Sets phone status to ACTIVE on success
     */
    @Transactional
    public PhoneNumberResponse verifyPhoneNumber(VerifyPhoneNumberRequest request) {
        log.info("Verifying phone number: phoneNumberId={}", request.getPhoneNumberId());

        WabaPhoneNumber phone = phoneNumberRepository.findById(request.getPhoneNumberId())
                .orElseThrow(() -> PhoneNumberNotFoundException.withId(request.getPhoneNumberId()));

        String accessToken = resolveAccessToken(phone);

        // Call Meta to verify the OTP
        metaApiClient.verifyCode(phone.getPhoneNumberId(), accessToken, request.getCode());

        // Update status to active
        phone.setStatus(PhoneNumberStatus.ACTIVE);
        phone = phoneNumberRepository.save(phone);

        log.info("Phone number verified successfully: {}", phone.getDisplayPhoneNumber());
        return WabaMapper.toPhoneNumberResponse(phone);
    }

    // ========================
    // SYNC FROM META
    // ========================

    /**
     * Pull phone numbers from Meta and upsert into local database.
     * Called during WABA creation and on manual sync requests.
     */
    @Transactional
    public void syncPhoneNumbersFromMeta(WabaAccount waba, String accessToken) {
        log.info("Syncing phone numbers from Meta: wabaId={}", waba.getWabaId());

        var metaResponse = metaApiClient.getPhoneNumbers(waba.getWabaId(), accessToken);

        if (!Boolean.TRUE.equals(metaResponse.getSuccess()) || metaResponse.getData() == null) {
            log.warn("Meta API returned no data during phone sync for wabaId={}", waba.getWabaId());
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phonesData =
                (List<Map<String, Object>>) metaResponse.getData().get("data");

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
                log.error("Failed to sync phone number: data={}, error={}", phoneData, ex.getMessage());
            }
        }

        log.info("Phone sync complete: {}/{} synced for wabaId={}", synced, phonesData.size(), waba.getWabaId());
    }

    // ========================
    // PRIVATE HELPERS
    // ========================

    /**
     * Upsert a single phone number from Meta data
     * Insert if new, update if already exists
     */
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
        try {
            return PhoneNumberStatus.fromValue(str(data, "status").toLowerCase());
        } catch (Exception e) {
            return PhoneNumberStatus.ACTIVE;
        }
    }

    private QualityRating parseQuality(Map<String, Object> data) {
        try {
            return QualityRating.valueOf(str(data, "quality_rating").toUpperCase());
        } catch (Exception e) {
            return QualityRating.UNKNOWN;
        }
    }

    private MessagingLimitTier parseTier(Map<String, Object> data) {
        try {
            return MessagingLimitTier.valueOf(str(data, "messaging_limit_tier").toUpperCase());
        } catch (Exception e) {
            return MessagingLimitTier.TIER_1K;
        }
    }

    /** Safe string extraction from Map, returns empty string if key missing */
    private String str(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    /**
     * Resolve the Meta access token for a given phone number.
     * Traverses: WabaPhoneNumber → WabaAccount → MetaOAuthAccount → accessToken
     */
    private String resolveAccessToken(WabaPhoneNumber phone) {
        WabaAccount waba = wabaRepository.findById(phone.getWabaAccountId())
                .orElseThrow(() -> WabaNotFoundException.withId(phone.getWabaAccountId()));

        return metaOAuthRepository.findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException("OAuth account not found for WABA: " + waba.getId()))
                .getAccessToken();
    }
}