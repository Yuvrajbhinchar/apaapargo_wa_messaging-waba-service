package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.dto.response.ApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaHealthResponse;
import com.aigreentick.services.wabaaccounts.service.HealthCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Health check endpoint for WABA integrations.
 *
 * Runs all 4 Meta API checks in a single request:
 *   1. Access token debug (is_valid, expiry, scopes)
 *   2. WABA account review status
 *   3. Phone number status + quality rating
 *   4. Business profile existence
 *
 * Usage:
 *   GET /embedded-signup/health/{organizationId}
 *
 * Returns overall_status:
 *   HEALTHY   → all checks passed
 *   DEGRADED  → some issues but messaging may still work
 *   UNHEALTHY → token invalid, messaging is BROKEN NOW
 */
@RestController
@RequestMapping("/embedded-signup")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Embedded Signup", description = "WABA onboarding and health checks")
public class HealthCheckController {

    private final HealthCheckService healthCheckService;

    /**
     * GET /embedded-signup/health/{organizationId}
     *
     * Runs a full health diagnostic against Meta APIs for the given organization.
     * Safe to poll — all operations are read-only.
     *
     * Typical use:
     *   - Dashboard "Integration Status" widget
     *   - Pre-send validation before a campaign
     *   - Ops monitoring (call daily / before business hours)
     *
     * Note: This makes several Meta API calls, so expect 1–3s response time.
     * Do NOT call on every message send — cache the result for at least 5 minutes.
     */
    @GetMapping("/health/{organizationId}")
    @Operation(
            summary = "Full WABA integration health check",
            description = "Runs token debug, WABA review status, phone number status, " +
                    "and business profile checks against Meta APIs. " +
                    "Returns HEALTHY / DEGRADED / UNHEALTHY overall status. " +
                    "Read-only. Cache result for ≥5 minutes between calls."
    )
    public ResponseEntity<ApiResponse<WabaHealthResponse>> checkHealth(
            @Parameter(description = "Organization ID to check", example = "42")
            @PathVariable Long organizationId) {

        log.info("Health check requested: orgId={}", organizationId);

        WabaHealthResponse health = healthCheckService.checkHealth(organizationId);

        // Return 200 always — the health status is expressed in the body, not HTTP status.
        // Callers should read overall_status, not rely on HTTP code for health state.
        return ResponseEntity.ok(
                ApiResponse.success(health, "Health check complete: " + health.getOverallStatus()));
    }
}