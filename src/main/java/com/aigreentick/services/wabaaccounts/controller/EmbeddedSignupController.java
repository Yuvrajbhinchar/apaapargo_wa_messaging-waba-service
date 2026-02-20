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
import java.util.Map;

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

    @GetMapping("/callback/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long taskId) {
        OnboardingTask task = orchestrator.getTask(taskId);

        Map<String, Object> body = new LinkedHashMap<>();
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
                body.put("processing_stuck", task.isStaleProcessing());
                if (task.isStaleProcessing()) {
                    body.put("stuck_since",      task.getStartedAt());
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

    @GetMapping("/callback/active/{organizationId}")
    public ResponseEntity<Map<String, Object>> getActiveTask(
            @PathVariable Long organizationId) {

        return orchestrator.findActiveTaskForOrg(organizationId)
                .map(task -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("task_id",          task.getId());
                    body.put("status",           task.getStatus().name());
                    body.put("processing_stuck", task.isStaleProcessing());
                    if (task.isStaleProcessing()) {
                        body.put("cancel_available", true);
                    }
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.<Map<String, Object>>noContent().build());
    }

    // ════════════════════════════════════════════════════════════
    // GET /embedded-signup/callback/failed/{organizationId}
    // ════════════════════════════════════════════════════════════

    @GetMapping("/callback/failed/{organizationId}")
    public ResponseEntity<Map<String, Object>> getLastFailedTask(
            @PathVariable Long organizationId) {

        return orchestrator.findLastFailedTaskForOrg(organizationId)
                .map(task -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("task_id",         task.getId());
                    body.put("status",          task.getStatus().name());
                    body.put("error_message",   task.getErrorMessage());
                    body.put("action_required", resolveAction(task));
                    body.put("retry_count",     task.getRetryCount());
                    body.put("updated_at",      task.getUpdatedAt());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.<Map<String, Object>>noContent().build());
    }

    // ════════════════════════════════════════════════════════════
    // POST /embedded-signup/callback/{taskId}/cancel
    // ════════════════════════════════════════════════════════════

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

            boolean wasAlreadyCancelled =
                    cancelled.getErrorMessage() != null &&
                            !cancelled.getErrorMessage().startsWith("Cancelled: " + reason);

            log.info("Task {} cancel request by org {} — already_cancelled={}",
                    taskId, organizationId, wasAlreadyCancelled);

            return ResponseEntity.ok(Map.of(
                    "task_id",           cancelled.getId(),
                    "status",            cancelled.getStatus().name(),
                    "already_cancelled", wasAlreadyCancelled,
                    "message",           "Task cancelled. Restart the embedded signup flow to try again.",
                    "next_step",         "Re-launch the Facebook Login SDK to get a new authorization code."
            ));

        } catch (IllegalStateException ex) {
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

        return null;
    }
}