package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.exception.WebhookVerificationException;
import com.aigreentick.services.wabaaccounts.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for handling Meta WhatsApp webhook events
 * Meta sends WABA-related events (status changes, quality updates, etc.)
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Meta WhatsApp webhook handlers")
public class WebhookController {

    private final WebhookService webhookService;
    private final MetaApiConfig metaApiConfig;

    /**
     * GET - Meta webhook verification (challenge-response)
     * Meta calls this once to verify your endpoint
     */
    @GetMapping("/whatsapp")
    @Operation(summary = "Meta webhook verification endpoint")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge
    ) {
        log.info("Webhook verification request received - mode: {}", mode);

        if (!"subscribe".equals(mode)) {
            throw new WebhookVerificationException("Invalid hub.mode: " + mode);
        }

        if (!metaApiConfig.getWebhookVerifyToken().equals(token)) {
            log.warn("Webhook verification failed - token mismatch");
            throw new WebhookVerificationException("Invalid verify token");
        }

        log.info("Webhook verified successfully");
        return ResponseEntity.ok(challenge);
    }

    /**
     * POST - Receive Meta webhook events
     * Must respond 200 OK within 5 seconds, then process async
     */
    @PostMapping("/whatsapp")
    @Operation(summary = "Receive Meta webhook events")
    public ResponseEntity<Void> handleWebhookEvent(
            @RequestBody Map<String, Object> payload
    ) {
        log.debug("Webhook event received");

        // Fire and forget - respond 200 immediately
        webhookService.processWebhookAsync(payload);

        return ResponseEntity.ok().build();
    }
}