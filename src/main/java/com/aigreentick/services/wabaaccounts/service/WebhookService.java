package com.aigreentick.services.wabaaccounts.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Async entry point for Meta webhook events
 *
 * IMPORTANT DESIGN NOTE:
 * This class is ONLY responsible for async dispatch.
 * It does NOT contain @Transactional — mixing @Async + @Transactional
 * on the same method breaks Spring's transaction context (transaction is
 * thread-bound and gets lost on thread switch).
 *
 * All DB operations are delegated to WebhookProcessor which is @Transactional.
 *
 * Flow:
 * WebhookController → (200 OK immediately) → WebhookService (async thread)
 *                                                    → WebhookProcessor (@Transactional)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookProcessor webhookProcessor;

    /**
     * Receive webhook payload and process asynchronously
     * The HTTP controller returns 200 OK immediately — this runs in background
     *
     * @param payload raw Meta webhook payload
     */
    @Async("webhookTaskExecutor")
    public void processWebhookAsync(Map<String, Object> payload) {
        log.debug("Webhook picked up by async executor");

        try {
            webhookProcessor.process(payload);
        } catch (Exception ex) {
            // Never let exceptions bubble out of @Async methods
            // They get swallowed silently if not caught — better to log them
            log.error("Webhook processing failed: {}", ex.getMessage(), ex);
        }
    }
}