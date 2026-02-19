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

import java.util.Map;

/**
 * ══════════════════════════════════════════════════════════════════
 * Embedded Signup Controller — Async Pattern
 * ══════════════════════════════════════════════════════════════════
 *
 * WHY THIS IS ASYNC
 * ─────────────────
 * The old synchronous flow held the HTTP thread for 8–12 seconds:
 *
 *   POST /callback  ──► EmbeddedSignupService.process() (8–12s) ──► 200 OK
 *
 * Under load, load balancers (and Meta's own retry logic) would
 * trigger a second callback before the first had finished, creating
 * duplicate WABA accounts and double token exchanges.
 *
 * New async flow:
 *
 *   POST /callback  ──► validate ──► save task ──► return 202 + taskId  (< 100ms)
 *                                        ↓
 *                              OnboardingOrchestrator
 *                              runs on background thread
 *                                        ↓
 *   GET /callback/status/{taskId}  ──► { status, wabaAccountId, error }
 *
 * FRONTEND INTEGRATION
 * ─────────────────────
 *   1. Receive 202 → store taskId
 *   2. Poll GET /embedded-signup/callback/status/{taskId} every 2 seconds
 *   3. Stop polling when status == COMPLETED or FAILED
 *   4. On COMPLETED → read wabaAccountId and redirect to dashboard
 *   5. On FAILED → show error message to user
 *
 * IDEMPOTENCY
 * ────────────
 * If the frontend retries the POST (e.g. user clicks twice), both calls
 * succeed with 202 but create separate tasks. The second task will likely
 * fail during WABA verification because Meta only accepts each OAuth code
 * once. The first task's completion wins. Consider adding a client-side
 * idempotency key in future.
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

    /**
     * Receive the embedded signup callback from Meta SDK.
     *
     * Validates input, persists an OnboardingTask, and returns immediately.
     * Actual processing happens on the onboardingTaskExecutor thread pool.
     *
     * @return 202 Accepted with task_id for frontend polling
     */
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
     * Poll the status of an onboarding task.
     *
     * Frontend polls this every 2s until status is COMPLETED or FAILED.
     *
     * Response shape:
     * <pre>
     * {
     *   "task_id": 42,
     *   "status": "COMPLETED",           // PENDING | PROCESSING | COMPLETED | FAILED
     *   "waba_account_id": 123,          // present on COMPLETED
     *   "result_summary": "...",         // present on COMPLETED (JSON)
     *   "error_message": "...",          // present on FAILED
     *   "retry_count": 0,
     *   "created_at": "2026-02-19T...",
     *   "updated_at": "2026-02-19T..."
     * }
     * </pre>
     *
     * @return 200 with task state, 404 if task not found
     */
    @GetMapping("/callback/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long taskId) {
        OnboardingTask task = orchestrator.getTask(taskId);

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("task_id",         task.getId());
        body.put("status",          task.getStatus().name());
        body.put("retry_count",     task.getRetryCount());
        body.put("created_at",      task.getCreatedAt());
        body.put("updated_at",      task.getUpdatedAt());

        if (task.getStatus() == OnboardingTask.Status.COMPLETED) {
            body.put("waba_account_id", task.getResultWabaAccountId());
            body.put("result_summary",  task.getResultSummary());
        }

        if (task.getStatus() == OnboardingTask.Status.FAILED) {
            body.put("error_message", task.getErrorMessage());
        }

        return ResponseEntity.ok(body);
    }
}