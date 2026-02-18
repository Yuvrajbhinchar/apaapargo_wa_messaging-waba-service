package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.config.MetaApiConfig;
import com.aigreentick.services.wabaaccounts.exception.WebhookVerificationException;
import com.aigreentick.services.wabaaccounts.service.WebhookService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Webhook Controller for Meta WhatsApp Business API events.
 *
 * Security:
 *   GET  — Challenge-response verification (proves endpoint ownership to Meta)
 *   POST — HMAC-SHA256 signature verification (X-Hub-Signature-256 header)
 *          Prevents spoofed events from non-Meta sources.
 *
 * Flow: Controller responds 200 OK immediately → WebhookService (async)
 *       → WebhookProcessor (@Transactional) handles DB updates on a thread pool
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Meta WhatsApp webhook endpoint")
public class WebhookController {

    private final WebhookService webhookService;
    private final MetaApiConfig metaApiConfig;
    private final ObjectMapper objectMapper;

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    /**
     * GET — Meta webhook verification (challenge-response handshake).
     * Meta calls this once when you register the webhook URL.
     */
    @GetMapping("/whatsapp")
    @Operation(summary = "Meta webhook verification — challenge response")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge")    String challenge) {

        log.info("Webhook verification request — mode={}", mode);

        if (!"subscribe".equals(mode)) {
            throw new WebhookVerificationException("Invalid hub.mode: " + mode);
        }
        if (!metaApiConfig.getWebhookVerifyToken().equals(token)) {
            log.warn("Webhook verify token mismatch");
            throw new WebhookVerificationException("Invalid verify token");
        }

        log.info("Webhook verified");
        return ResponseEntity.ok(challenge);
    }

    /**
     * POST — Receive and process Meta webhook events.
     *
     * Raw body is read as String to enable HMAC-SHA256 signature verification
     * before any processing. Responds 200 immediately — processing is async.
     */
    @PostMapping(value = "/whatsapp", consumes = "application/json")
    @Operation(summary = "Receive Meta webhook events (async processing)")
    public ResponseEntity<Void> handleWebhookEvent(
            @RequestBody String rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signatureHeader) {

        log.debug("Webhook event received");

        verifySignature(rawBody, signatureHeader);

        Map<String, Object> payload = parsePayload(rawBody);
        webhookService.processWebhookAsync(payload);

        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ───────────────────────────────────────────────────────────

    private void verifySignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            log.warn("Missing or malformed {} header", SIGNATURE_HEADER);
            throw new WebhookVerificationException("Missing X-Hub-Signature-256 header");
        }
        try {
            String received = signatureHeader.substring("sha256=".length());
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    metaApiConfig.getAppSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            String computed = HexFormat.of().formatHex(
                    mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));

            if (!constantTimeEquals(received, computed)) {
                log.warn("Webhook HMAC mismatch — rejecting event");
                throw new WebhookVerificationException("Invalid webhook signature");
            }
            log.debug("Webhook signature verified OK");
        } catch (WebhookVerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Signature verification error: {}", ex.getMessage());
            throw new WebhookVerificationException("Signature verification failed");
        }
    }

    /** Constant-time comparison prevents timing attacks */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private Map<String, Object> parsePayload(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception ex) {
            log.error("Webhook JSON parse error: {}", ex.getMessage());
            throw new WebhookVerificationException("Invalid JSON in webhook payload");
        }
    }
}