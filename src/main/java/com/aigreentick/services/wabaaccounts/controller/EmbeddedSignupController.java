package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.ApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.dto.response.SignupConfigResponse;
import com.aigreentick.services.wabaaccounts.service.EmbeddedSignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Meta WhatsApp Embedded Signup flow.
 *
 * This is the onboarding entry point — equivalent to what WATI, AiSensy,
 * Interakt expose as their "Connect WhatsApp" feature.
 *
 * Endpoints:
 *   GET  /api/v1/embedded-signup/config    → SDK configuration for frontend
 *   POST /api/v1/embedded-signup/callback  → Process OAuth code after signup
 *   GET  /api/v1/embedded-signup/status/{orgId} → Check org's WABA connection status
 *
 * Security:
 *   - /config and /callback are publicly accessible (called from browser)
 *   - /status requires X-Organization-Id header (set by gateway)
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/embedded-signup")
@RequiredArgsConstructor
@Slf4j
@Tag(
        name = "Embedded Signup",
        description = "Meta WhatsApp Business Account onboarding via Embedded Signup flow. " +
                "Use these endpoints to connect your customers' WhatsApp Business Accounts."
)
public class EmbeddedSignupController {

    private final EmbeddedSignupService embeddedSignupService;

    /**
     * Step 1 — Frontend fetches SDK config before showing "Connect WhatsApp" button.
     *
     * Frontend usage:
     * <pre>
     * const config = await fetch('/api/v1/embedded-signup/config').then(r => r.json());
     * FB.init({ appId: config.data.metaAppId, version: config.data.apiVersion });
     * </pre>
     */
    @GetMapping("/config")
    @Operation(
            summary = "Get Meta SDK configuration for embedded signup",
            description = "Returns app ID, scopes, and extras needed to initialize Facebook Login SDK on the frontend"
    )
    public ResponseEntity<ApiResponse<SignupConfigResponse>> getSignupConfig() {
        log.debug("GET /embedded-signup/config");
        SignupConfigResponse config = embeddedSignupService.getSignupConfig();
        return ResponseEntity.ok(ApiResponse.success(config, "Signup configuration fetched"));
    }

    /**
     * Step 2 — Frontend sends OAuth code here after user completes Meta signup popup.
     *
     * This is the core onboarding endpoint. It:
     *  1. Exchanges the code for a long-lived access token
     *  2. Creates the WABA account in our system
     *  3. Subscribes to Meta webhook events
     *  4. Syncs phone numbers from Meta
     *
     * Frontend usage:
     * <pre>
     * FB.login((response) => {
     *   if (response.authResponse) {
     *     const { code, ...sessionInfo } = JSON.parse(response.authResponse.code);
     *     await fetch('/api/v1/embedded-signup/callback', {
     *       method: 'POST',
     *       body: JSON.stringify({
     *         organizationId: currentOrg.id,
     *         code: code,
     *         wabaId: sessionInfo.waba_id,
     *         businessManagerId: sessionInfo.business_id
     *       })
     *     });
     *   }
     * }, {
     *   config_id: '<your-embedded-signup-config-id>',
     *   response_type: 'code',
     *   override_default_response_type: true,
     *   extras: { sessionInfoVersion: '3' }
     * });
     * </pre>
     */
    @PostMapping("/callback")
    @Operation(
            summary = "Process Meta OAuth callback — completes WABA onboarding",
            description = "Exchanges the short-lived OAuth code for a long-lived token, " +
                    "creates the WABA account, subscribes to webhooks, and syncs phone numbers. " +
                    "Returns the fully onboarded WABA with phone numbers."
    )
    public ResponseEntity<ApiResponse<EmbeddedSignupResponse>> handleCallback(
            @Valid @RequestBody EmbeddedSignupCallbackRequest request
    ) {
        log.info("POST /embedded-signup/callback - orgId={}, wabaId={}",
                request.getOrganizationId(), request.getWabaId());

        EmbeddedSignupResponse response = embeddedSignupService.processSignupCallback(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, response.getSummary()));
    }

    /**
     * Check the WABA connection status for a given organization.
     * Frontend polls this to show "Connected" / "Disconnected" badge.
     */
    @GetMapping("/status/{organizationId}")
    @Operation(
            summary = "Check WABA connection status for an organization",
            description = "Returns whether the organization has a connected WABA and its current status"
    )
    public ResponseEntity<ApiResponse<Boolean>> getConnectionStatus(
            @PathVariable Long organizationId
    ) {
        log.debug("GET /embedded-signup/status/{}", organizationId);

        // Simple check — if org has at least one ACTIVE WABA, they're connected
        // The WabaRepository already has this query
        boolean connected = embeddedSignupService.isOrganizationConnected(organizationId);

        String message = connected
                ? "WhatsApp Business Account is connected and active"
                : "No active WhatsApp Business Account found for this organization";

        return ResponseEntity.ok(ApiResponse.success(connected, message));
    }
}