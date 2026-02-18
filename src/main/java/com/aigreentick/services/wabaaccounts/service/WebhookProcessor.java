package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus;
import com.aigreentick.services.wabaaccounts.constants.QualityRating;
import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaPhoneNumberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Transactional processor for Meta webhook events
 *
 * Handles WABA-related events:
 * - account_update:               WABA banned / reinstated
 * - phone_number_quality_update:  Quality rating changed (GREEN/YELLOW/RED)
 * - phone_number_name_update:     Business name approved/rejected
 * - account_review_update:        Meta account review decision
 *
 * NOTE: This class is intentionally SEPARATE from WebhookService.
 * @Async and @Transactional cannot coexist on the same method because
 * Spring's transaction is thread-bound and gets lost on async thread switch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookProcessor {

    private final WabaAccountRepository wabaRepository;
    private final WabaPhoneNumberRepository phoneNumberRepository;

    // Meta webhook field names
    private static final String OBJECT_WHATSAPP = "whatsapp_business_account";
    private static final String FIELD_ACCOUNT_UPDATE = "account_update";
    private static final String FIELD_QUALITY_UPDATE = "phone_number_quality_update";
    private static final String FIELD_NAME_UPDATE = "phone_number_name_update";
    private static final String FIELD_REVIEW_UPDATE = "account_review_update";

    /**
     * Main entry point — processes full Meta webhook payload
     * Each entry/change is handled in its own transaction
     */
    @Transactional
    public void process(Map<String, Object> payload) {
        log.debug("Processing webhook payload");

        String objectType = String.valueOf(payload.getOrDefault("object", ""));

        if (!OBJECT_WHATSAPP.equals(objectType)) {
            log.debug("Ignoring non-WABA webhook object: {}", objectType);
            return;
        }

        List<Map<String, Object>> entries = extractList(payload, "entry");
        if (entries == null || entries.isEmpty()) {
            log.warn("Webhook received with no entries");
            return;
        }

        for (Map<String, Object> entry : entries) {
            processEntry(entry);
        }
    }

    // ========================
    // PRIVATE — Entry + Change
    // ========================

    private void processEntry(Map<String, Object> entry) {
        String wabaId = String.valueOf(entry.getOrDefault("id", ""));
        List<Map<String, Object>> changes = extractList(entry, "changes");

        if (changes == null || changes.isEmpty()) {
            return;
        }

        for (Map<String, Object> change : changes) {
            String field = String.valueOf(change.getOrDefault("field", ""));
            Map<String, Object> value = extractMap(change, "value");

            if (value == null) {
                log.warn("Webhook change has no value: field={}", field);
                continue;
            }

            log.info("Processing webhook: field={}, wabaId={}", field, wabaId);

            switch (field) {
                case FIELD_ACCOUNT_UPDATE  -> handleAccountUpdate(wabaId, value);
                case FIELD_QUALITY_UPDATE  -> handleQualityUpdate(value);
                case FIELD_NAME_UPDATE     -> handleNameUpdate(value);
                case FIELD_REVIEW_UPDATE   -> handleAccountReviewUpdate(wabaId, value);
                default                    -> log.debug("Unhandled webhook field: {}", field);
            }
        }
    }

    // ========================
    // EVENT HANDLERS
    // ========================

    /**
     * Handle WABA account status change (banned / reinstated)
     */
    private void handleAccountUpdate(String wabaId, Map<String, Object> value) {
        String event = String.valueOf(value.getOrDefault("event", "")).toUpperCase();
        log.info("Account update event: wabaId={}, event={}", wabaId, event);

        wabaRepository.findByWabaId(wabaId).ifPresentOrElse(
                waba -> {
                    switch (event) {
                        case "BANNED" -> {
                            waba.setStatus(WabaStatus.SUSPENDED);
                            wabaRepository.save(waba);
                            log.warn("🔴 WABA BANNED by Meta: wabaId={}", wabaId);
                        }
                        case "REINSTATED" -> {
                            waba.setStatus(WabaStatus.ACTIVE);
                            wabaRepository.save(waba);
                            log.info("✅ WABA reinstated: wabaId={}", wabaId);
                        }
                        default -> log.debug("Unhandled account event: {}", event);
                    }
                },
                () -> log.warn("Received account_update for unknown WABA: {}", wabaId)
        );
    }

    /**
     * Handle phone number quality rating change (GREEN / YELLOW / RED)
     */
    private void handleQualityUpdate(Map<String, Object> value) {
        String phoneNumberId = String.valueOf(value.getOrDefault("phone_number", ""));
        String rawRating = String.valueOf(value.getOrDefault("new_rating", "UNKNOWN")).toUpperCase();

        log.info("Quality update: phoneNumberId={}, newRating={}", phoneNumberId, rawRating);

        QualityRating rating;
        try {
            rating = QualityRating.valueOf(rawRating);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown quality rating received: {}", rawRating);
            rating = QualityRating.UNKNOWN;
        }

        int updated = phoneNumberRepository.updateQualityRating(phoneNumberId, rating);

        if (updated > 0) {
            log.info("Quality rating updated: phoneNumberId={}, rating={}", phoneNumberId, rating);

            if (QualityRating.RED.equals(rating)) {
                log.warn("🔴 CRITICAL: Phone {} has RED quality — risk of messaging ban!", phoneNumberId);
            } else if (QualityRating.YELLOW.equals(rating)) {
                log.warn("🟡 WARNING: Phone {} quality dropped to YELLOW", phoneNumberId);
            }
        } else {
            log.warn("Quality update received for unknown phone: {}", phoneNumberId);
        }
    }

    /**
     * Handle phone number business name approval/rejection
     */
    private void handleNameUpdate(Map<String, Object> value) {
        String phoneNumberId = String.valueOf(value.getOrDefault("phone_number_id", ""));
        String decision = String.valueOf(value.getOrDefault("decision", "")).toUpperCase();

        log.info("Name update: phoneNumberId={}, decision={}", phoneNumberId, decision);

        phoneNumberRepository.findByPhoneNumberId(phoneNumberId).ifPresent(phone -> {
            if ("APPROVED".equals(decision)) {
                phone.setStatus(PhoneNumberStatus.ACTIVE);
                phoneNumberRepository.save(phone);
                log.info("Phone name approved, status set to ACTIVE: {}", phoneNumberId);
            } else if ("REJECTED".equals(decision)) {
                log.warn("Phone name rejected: {}", phoneNumberId);
            }
        });
    }

    /**
     * Handle WABA account review decision (Meta compliance review)
     */
    private void handleAccountReviewUpdate(String wabaId, Map<String, Object> value) {
        String decision = String.valueOf(value.getOrDefault("decision", "")).toUpperCase();
        log.info("Account review update: wabaId={}, decision={}", wabaId, decision);

        wabaRepository.findByWabaId(wabaId).ifPresent(waba -> {
            if ("REJECTED".equals(decision)) {
                waba.setStatus(WabaStatus.SUSPENDED);
                wabaRepository.save(waba);
                log.warn("🔴 WABA account review REJECTED by Meta: wabaId={}", wabaId);
            } else if ("APPROVED".equals(decision)) {
                log.info("✅ WABA account review APPROVED: wabaId={}", wabaId);
            }
        });
    }

    // ========================
    // SAFE CAST HELPERS
    // ========================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}