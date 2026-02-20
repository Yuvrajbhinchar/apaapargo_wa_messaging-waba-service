package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.service.OnboardingOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded Signup Controller
 *
 * Endpoints:
 *   POST   /embedded-signup/callback                      — receive Meta SDK callback
 *   GET    /embedded-signup/callback/status/{taskId}      — poll task status
 *   GET    /embedded-signup/callback/active/{orgId}       — recover in-flight taskId
 *   GET    /embedded-signup/callback/failed/{orgId}       — get last failed task (EDGE CASE 3)
 *   POST   /embedded-signup/callback/{taskId}/cancel      — self-service reset
 *
 * ═══ Edge Case Fixes ══════════════════════════════════════════════════════
 *
 * EDGE CASE 1 — Stale PROCESSING tasks are cancellable
 *   /cancel now works for PROCESSING tasks older than STUCK_PROCESSING_MINUTES.
 *   The status response includes processing_stuck=true so the frontend knows
 *   when to offer the cancel button for a PROCESSING task.
 *
 * EDGE CASE 2 — Double-cancel is safe (idempotent)
 *   Calling /cancel on an already-CANCELLED task returns 200, not 409.
 *   The state service handles this; the controller just passes it through.
 *
 * EDGE CASE 3 — FAILED is not "active"
 *   GET /active/{orgId} only returns PENDING and PROCESSING tasks.
 *   GET /failed/{orgId} is a new endpoint to surface the last failure so the
 *   frontend can show the action_required state even after a page refresh.
 *
 * ═══ Frontend Integration (complete state machine) ════════════════════════
 *
 *   On page load:
 *     1. GET /active/{orgId}
 *        → 200 with task_id: start polling /status/{taskId}
 *        → 204 no active task: GET /failed/{orgId}
 *           → 200 with action_required: show "Retry" or "Contact support"
 *           → 204 no failure: show "Connect WhatsApp" button
 *
 *   During polling:
 *     PENDING / PROCESSING (fresh)    → keep polling
 *     PROCESSING + processing_stuck   → show cancel button after X minutes
 *     COMPLETED                       → redirect to dashboard
 *     FAILED + RESTART_SIGNUP         → POST /cancel → re-launch FB.login()
 *     FAILED + CONTACT_SUPPORT        → show support message
 *
 *   On cancel:
 *     POST /cancel → 200 (task now CANCELLED, or was already CANCELLED)
 *     → re-launch FB.login() → new code → POST /callback → new task_id
 */
@RestController
@RequestMapping("/embedded-signup")
@RequiredArgsConstructor
@Slf4j
public class EmbeddedSignupController {

    private final OnboardingOrchestrator orchestrator;

    // ════════════════════════════════════════════════════════════
    // POST /embedded-signup/callback
    // ════════════════════════════════════════════════════════════

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @Valid @RequestBody EmbeddedSignupCallbackRequest request) {

        log.info("Embedded signup callback received: orgId={}, wabaId={}",
                request.getOrganizationId(), request.getWabaId());

        Long taskId = orchestrator.enqueue(request);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status",  "PENDING",
                        "task_id", taskId,
                        "message", "Onboarding started. Poll /embedded-signup/callback/status/" + taskId
                ));
    }

    // ════════════════════════════════════════════════════════════
    // GET /embedded-signup/callback/status/{taskId}
    // ════════════════════════════════════════════════════════════

    /**
     * Poll task status.
     *
     * Key fields:
     *   action_required    — present on FAILED: RESTART_SIGNUP | CONTACT_SUPPORT | null
     *   processing_stuck   — present on PROCESSING: true if older than threshold
     *                        Frontend should show a cancel button when this is true.
     */
    @GetMapping("/callback/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long taskId) {
        OnboardingTask task = orchestrator.getTask(taskId);

        var body = new LinkedHashMap<String, Object>();
        body.put("task_id",     task.getId());
        body.put("status",      task.getStatus().name());
        body.put("retry_count", task.getRetryCount());
        body.put("created_at",  task.getCreatedAt());
        body.put("updated_at",  task.getUpdatedAt());

        switch (task.getStatus()) {
            case COMPLETED -> {
                body.put("waba_account_id", task.getResultWabaAccountId());
                body.put("result_summary",  task.getResultSummary());
            }
            case FAILED -> {
                body.put("error_message",  task.getErrorMessage());
                body.put("action_required", resolveAction(task));
            }
            case PROCESSING -> {
                // EDGE CASE 1: tell the frontend when a PROCESSING task is stale
                // so it can offer the cancel button without waiting for a full failure
                body.put("processing_stuck", task.isStaleProcessing());
                if (task.isStaleProcessing()) {
                    body.put("stuck_since",   task.getStartedAt());
                    body.put("cancel_available", true);
                }
            }
            case CANCELLED ->
                    body.put("message", "Task cancelled. Restart the signup flow to try again.");
            default -> { /* PENDING — no extra fields needed */ }
        }

        return ResponseEntity.ok(body);
    }

    // ════════════════════════════════════════════════════════════
    // GET /embedded-signup/callback/active/{organizationId}
    // ════════════════════════════════════════════════════════════

    /**
     * EDGE CASE 3 FIX: Returns only PENDING and PROCESSING tasks.
     * FAILED is explicitly excluded — it is NOT active from the UX perspective.
     *
     * Returns 200 with task if one is in-flight, 204 if none.
     */
    @GetMapping("/callback/active/{organizationId}")
    public ResponseEntity<Map<String, Object>> getActiveTask(
            @PathVariable Long organizationId) {

        return orchestrator.findActiveTaskForOrg(organizationId)
                .map(task -> {
                    var body = new LinkedHashMap<String, Object>();
                    body.put("task_id",          task.getId());
                    body.put("status",            task.getStatus().name());
                    body.put("processing_stuck",  task.isStaleProcessing());
                    if (task.isStaleProcessing()) {
                        body.put("cancel_available", true);
                    }
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.noContent().build());
    }

    // ════════════════════════════════════════════════════════════
    // GET /embedded-signup/callback/failed/{organizationId}
    // ════════════════════════════════════════════════════════════

    /**
     * EDGE CASE 3 FIX: New endpoint to surface the last FAILED task after a
     * page refresh when there is no active task.
     *
     * Without this, a customer who had a FAILED task and refreshed the page
     * would see "no active task" (correct) but lose the action_required signal,
     * leaving them with just a blank "Connect WhatsApp" button and no indication
     * that their last attempt failed.
     *
     * Returns 200 with the most recent FAILED task, 204 if none.
     */
    @GetMapping("/callback/failed/{organizationId}")
    public ResponseEntity<Map<String, Object>> getLastFailedTask(
            @PathVariable Long organizationId) {

        return orchestrator.findLastFailedTaskForOrg(organizationId)
                .map(task -> {
                    var body = new LinkedHashMap<String, Object>();
                    body.put("task_id",        task.getId());
                    body.put("status",         task.getStatus().name());
                    body.put("error_message",  task.getErrorMessage());
                    body.put("action_required", resolveAction(task));
                    body.put("retry_count",    task.getRetryCount());
                    body.put("updated_at",     task.getUpdatedAt());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.noContent().build());
    }

    // ════════════════════════════════════════════════════════════
    // POST /embedded-signup/callback/{taskId}/cancel
    // ════════════════════════════════════════════════════════════

    /**
     * Customer self-service reset.
     *
     * EDGE CASE 1: Works on stale PROCESSING tasks (stuck > threshold).
     * EDGE CASE 2: Idempotent — calling twice returns 200 both times.
     *
     * Returns 200 on success or already-cancelled.
     * Returns 409 if the task is not in a cancellable state.
     * Returns 403 if the org does not own the task.
     */
    @PostMapping("/callback/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) Map<String, Object> body) {

        Long organizationId = body != null && body.get("organization_id") != null
                ? Long.valueOf(body.get("organization_id").toString())
                : null;

        String reason = body != null && body.get("reason") != null
                ? body.get("reason").toString()
                : "user_initiated";

        if (organizationId == null) {
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "organization_id is required"));
        }

        try {
            OnboardingTask cancelled = orchestrator.cancelTask(taskId, organizationId, reason);

            // EDGE CASE 2: distinguish "just cancelled" from "already was cancelled"
            // Both return 200 — the message differs only for logging/debugging purposes.
            boolean wasAlreadyCancelled =
                    cancelled.getErrorMessage() != null &&
                            !cancelled.getErrorMessage().startsWith("Cancelled: " + reason);

            log.info("Task {} cancel request by org {} — already_cancelled={}",
                    taskId, organizationId, wasAlreadyCancelled);

            return ResponseEntity.ok(Map.of(
                    "task_id",       cancelled.getId(),
                    "status",        cancelled.getStatus().name(),
                    "already_cancelled", wasAlreadyCancelled,
                    "message",       "Task cancelled. Restart the embedded signup flow to try again.",
                    "next_step",     "Re-launch the Facebook Login SDK to get a new authorization code."
            ));

        } catch (IllegalStateException ex) {
            // Task exists but is not in a cancellable state
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error",   ex.getMessage(),
                            "task_id", taskId
                    ));

        } catch (SecurityException ex) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied"));
        }
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════

    private String resolveAction(OnboardingTask task) {
        String error = task.getErrorMessage();
        if (error == null) return "CONTACT_SUPPORT";

        if (error.contains("OAuth code already consumed")
                || error.contains("authorization code has been used")
                || error.contains("This authorization code")) {
            return "RESTART_SIGNUP";
        }

        if (task.getRetryCount() >= 3) {
            return "CONTACT_SUPPORT";
        }

        // Still within retry budget — scheduler will retry automatically
        return null;
    }
}