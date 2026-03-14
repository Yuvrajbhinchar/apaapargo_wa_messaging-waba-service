package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.dto.response.WabaCredentialsResponse;
import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaPhoneNumber;
import com.aigreentick.services.wabaaccounts.exception.PhoneNumberNotFoundException;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.MetaOAuthAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaPhoneNumberRepository;
import com.aigreentick.services.wabaaccounts.service.TokenEncryptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/internal/waba-accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal", description = "Service-to-service endpoints — VPC only, not public")
public class InternalWabaController {

    private final WabaAccountRepository wabaAccountRepository;
    private final MetaOAuthAccountRepository metaOAuthAccountRepository;
    private final WabaPhoneNumberRepository phoneNumberRepository;
    private final TokenEncryptionService tokenEncryptionService;

    /**
     * GET /internal/waba-accounts/{wabaAccountId}/credentials
     *
     * Returns the Meta credentials needed to send messages on behalf of a WABA.
     * Callers (e.g. campaign-service) call this once per campaign preparation,
     * then cache the result for the duration of the send window.
     *
     * Response fields:
     *   wabaAccountId — internal DB ID (echoed back for correlation)
     *   phoneNumberId — Meta's opaque phone number ID used as the 'from' value
     *   accessToken   — decrypted, ready-to-use Meta Bearer token
     *
     * Phone number notes:
     *   - phoneNumberId is Meta's numeric ID string, not the display number
     *   - If you need the human-readable number (for display / logging), call
     *     GET /api/v1/phone-numbers/waba/{wabaAccountId} instead
     *
     * Performance target: p99 < 2s
     *   - Two indexed PK lookups + one indexed FK lookup — sub-millisecond at scale
     *   - No Meta API calls; all data served from DB
     *
     * Error cases:
     *   404 WABA_NOT_FOUND         → wabaAccountId does not exist
     *   404 PHONE_NUMBER_NOT_FOUND → WABA has no active phone numbers
     *   404 WABA_NOT_FOUND         → OAuth account missing (re-run embedded signup)
     *   500                        → token decryption failure (key mismatch / corruption)
     */
    @GetMapping("/{wabaAccountId}/credentials")
    @Transactional(readOnly = true)
    @Operation(
            summary  = "Get Meta credentials for a WABA — INTERNAL ONLY",
            description =
                    "Returns the decrypted Meta access token and phone number ID for the given " +
                            "WABA account. Called by campaign-service before message dispatch. " +
                            "⚠️ Internal VPC only — never expose publicly."
    )
    public ResponseEntity<WabaCredentialsResponse> getCredentials(
            @PathVariable Long wabaAccountId) {

        log.debug("Credentials requested: wabaAccountId={}", wabaAccountId);

        // ── 1. Load WABA ────────────────────────────────────────────────────
        WabaAccount waba = wabaAccountRepository.findById(wabaAccountId)
                .orElseThrow(() -> WabaNotFoundException.withId(wabaAccountId));

        // ── 2. Load OAuth account (token) ───────────────────────────────────
        MetaOAuthAccount oauthAccount = metaOAuthAccountRepository
                .findById(waba.getMetaOAuthAccountId())
                .orElseThrow(() -> new WabaNotFoundException(
                        "OAuth account not found for WABA " + wabaAccountId +
                                ". Re-run embedded signup to reconnect."));

        // ── 3. Resolve active phone number ──────────────────────────────────
        //    Pick the first ACTIVE phone on this WABA.
        //    If the WABA has multiple phones, callers that need a specific one
        //    should pass the phoneNumberId directly in their campaign payload
        //    rather than relying on this endpoint.
        List<WabaPhoneNumber> activePhones = phoneNumberRepository
                .findByWabaAccountIdAndStatus(
                        wabaAccountId,
                        com.aigreentick.services.wabaaccounts.constants.PhoneNumberStatus.ACTIVE);

        if (activePhones.isEmpty()) {
            throw new PhoneNumberNotFoundException(
                    "No active phone numbers found for WABA " + wabaAccountId +
                            ". Register and activate a phone number first.");
        }

        // Take the first active phone — deterministic ordering by DB insertion order
        String metaPhoneNumberId = activePhones.get(0).getPhoneNumberId();

        // ── 4. Decrypt token ────────────────────────────────────────────────
        //    Decryption failure → 500 (infra, not business failure)
        String accessToken = tokenEncryptionService.decrypt(oauthAccount.getAccessToken());

        log.debug("Credentials resolved: wabaAccountId={}, phoneNumberId={}",
                wabaAccountId, metaPhoneNumberId);

        return ResponseEntity.ok(WabaCredentialsResponse.builder()
                .wabaAccountId(wabaAccountId)
                .phoneNumberId(metaPhoneNumberId)
                .accessToken(accessToken)
                .build());
    }
}