package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaPhoneNumberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Service to handle incoming Meta webhook events
 * Processes WABA-related events like status changes, quality rating updates
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WabaAccountRepository wabaRepository;
    private final WabaPhoneNumberRepository phoneNumberRepository;

    // Webhook event types from Meta
    private static final String EVENT_ACCOUNT_UPDATE = "account_update";
    private static final String EVENT_PHONE_NUMBER_QUALITY_UPDATE = "phone_number_quality_update";
    private static final String EVENT_PHONE_NUMBER_NAME_UPDATE = "phone_number_name_update";
    private static final String EVENT_ACCOUNT_REVIEW_UPDATE = "account_review_update";
    private static final String EVENT_BUSINESS_CAPABILITY_UPDATE = "business_capability_update";

    /**
     * Process webhook payload asynchronously
     * Immediate 200 OK is sent by controller, processing happens here
     */
    @Async
    @Transactional
    public void processWebhookAsync(Map<String, Object> payload) {
        log.debug("Processing webhook payload: {}", payload);

        try {
            String objectType = String.valueOf(payload.getOrDefault("object", ""));

            if (!"whatsapp_business_account".equals(objectType)) {
                log.debug("Ignoring non-WABA webhook: {}", objectType);
                return;
            }

            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) payload.get("entry");

            if (entries == null || entries.isEmpty()) {
                log.warn("Webhook received with no entries");
                return;
            }

            for (Map<String, Object> entry : entries) {
                processEntry(entry);
            }

        } catch (Exception ex) {
            log.error("Failed to process webhook payload: {}", ex.getMessage(), ex);
        }
    }

    private void processEntry(Map<String, Object> entry) {
        String wabaId = String.valueOf(entry.getOrDefault("id", ""));
        List<Map<String, Object>> changes =
                (List<Map<String, Object>>) entry.get("changes");

        if (changes == null) return;

        for (Map<String, Object> change : changes) {
            String field = String.valueOf(change.getOrDefault("field", ""));
            Map<String, Object> value = (Map<String, Object>) change.get("value");

            log.info("Processing webhook event: field={}, wabaId={}", field, wabaId);

            switch (field) {
                case EVENT_ACCOUNT_UPDATE -> handleAccountUpdate(wabaId, value);
                case EVENT_PHONE_NUMBER_QUALITY_UPDATE -> handleQualityUpdate(value);
                case EVENT_PHONE_NUMBER_NAME_UPDATE -> handleNameUpdate(value);
                case EVENT_ACCOUNT_REVIEW_UPDATE -> handleAccountReviewUpdate(wabaId, value);
                default -> log.debug("Unhandled webhook field: {}", field);
            }
        }
    }

    /**
     * Handle WABA account status change
     */
    private void handleAccountUpdate(String wabaId, Map<String, Object> value) {
        String event = String.valueOf(value.getOrDefault("event", ""));
        log.info("WABA account update: wabaId={}, event={}", wabaId, event);

        wabaRepository.findByWabaId(wabaId).ifPresent(waba -> {
            switch (event.toUpperCase()) {
                case "BANNED" -> {
                    waba.setStatus(WabaStatus.SUSPENDED);
                    wabaRepository.save(waba);
                    log.warn("WABA BANNED by Meta: wabaId={}", wabaId);
                }
                case "REINSTATED" -> {
                    waba.setStatus(WabaStatus.ACTIVE);
                    wabaRepository.save(waba);
                    log.info("WABA reinstated: wabaId={}", wabaId);
                }
                default -> log.debug("Unhandled account event: {}", event);
            }
        });
    }

    /**
     * Handle phone number quality rating change
     */
    private void handleQualityUpdate(Map<String, Object> value) {
        String phoneNumberId = String.valueOf(value.getOrDefault("phone_number", ""));
        String newRating = String.valueOf(value.getOrDefault("new_rating", "UNKNOWN")).toUpperCase();

        log.info("Quality rating update: phoneNumberId={}, newRating={}", phoneNumberId, newRating);

        try {
            QualityRating rating = QualityRating.valueOf(newRating);
            int updated = phoneNumberRepository.updateQualityRating(phoneNumberId, rating);

            if (updated > 0) {
                log.info("Quality rating updated for phone: {}, rating: {}", phoneNumberId, rating);

                if (QualityRating.RED.equals(rating)) {
                    log.warn("CRITICAL: Phone number {} has RED quality rating - risk of ban!", phoneNumberId);
                }
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown quality rating received: {}", newRating);
        }
    }

    /**
     * Handle phone number name update
     */
    private void handleNameUpdate(Map<String, Object> value) {
        String phoneNumberId = String.valueOf(value.getOrDefault("phone_number_id", ""));
        String newName = String.valueOf(value.getOrDefault("decision", ""));

        log.info("Phone name update: phoneNumberId={}, decision={}", phoneNumberId, newName);

        if ("APPROVED".equals(newName)) {
            phoneNumberRepository.findByPhoneNumberId(phoneNumberId)
                    .ifPresent(phone -> {
                        phone.setStatus(PhoneNumberStatus.ACTIVE);
                        phoneNumberRepository.save(phone);
                    });
        }
    }

    /**
     * Handle WABA account review status change
     */
    private void handleAccountReviewUpdate(String wabaId, Map<String, Object> value) {
        String decision = String.valueOf(value.getOrDefault("decision", ""));
        log.info("Account review update: wabaId={}, decision={}", wabaId, decision);

        wabaRepository.findByWabaId(wabaId).ifPresent(waba -> {
            if ("REJECTED".equals(decision)) {
                waba.setStatus(WabaStatus.SUSPENDED);
                wabaRepository.save(waba);
                log.warn("WABA account review REJECTED: wabaId={}", wabaId);
            }
        });
    }
}