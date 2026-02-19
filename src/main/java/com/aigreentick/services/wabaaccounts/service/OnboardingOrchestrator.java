package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.repository.OnboardingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════════
 * Onboarding Orchestrator — Async Worker
 * ══════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * The embedded signup flow is 8–12 seconds in the worst case:
 *   - token exchange          ~500ms
 *   - token extension         ~500ms
 *   - scope check             ~500ms
 *   - WABA ownership verify   ~500ms
 *   - webhook subscribe       ~500ms
 *   - webhook health check    ~500ms
 *   - phone sync              ~1–2s
 *   - system user (Phase 2)   ~2–5s with retries
 *
 * Running this on the HTTP request thread causes:
 *   - Frontend timeouts (most clients timeout at 10–30s)
 *   - 504 Gateway Timeout from load balancers/CDN (often 60s)
 *   - Double-submit risk: user retries, creating duplicate WABAs
 *
 * ARCHITECTURE
 * ─────────────
 * Controller  →  creates OnboardingTask (PENDING)  →  returns task_id  (<100ms)
 *                      ↓
 *             enqueues processTaskAsync()
 *                      ↓
 * Async thread picks up task → runs EmbeddedSignupService flow
 *                      ↓
 * Task updated: COMPLETED or FAILED
 *                      ↓
 * Frontend polls GET /embedded-signup/callback/status/{taskId}
 *
 * NOTE ON @Async + @Transactional
 * ────────────────────────────────
 * @Async switches threads. Spring's @Transactional is thread-bound.
 * If both annotations are on the same method, the transaction doesn't
 * propagate to the async thread — resulting in silent non-atomic operations.
 *
 * Solution (same pattern as WebhookService → WebhookProcessor):
 *   - This class: @Async only — dispatches work
 *   - EmbeddedSignupService: @Transactional only — does DB writes
 *   They call each other; the transaction opens on the ASYNC thread inside
 *   EmbeddedSignupService, which is correct.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingOrchestrator {

    private final OnboardingTaskRepository taskRepository;
    private final EmbeddedSignupService embeddedSignupService;

    // ════════════════════════════════════════════════════════════
    // ENQUEUE — called from controller thread (fast path)
    // ════════════════════════════════════════════════════════════

    /**
     * Save an onboarding task and trigger async processing.
     *
     * @return the task ID — returned to frontend immediately
     */
    @Transactional
    public Long enqueue(EmbeddedSignupCallbackRequest request) {
        OnboardingTask task = OnboardingTask.builder()
                .organizationId(request.getOrganizationId())
                .oauthCode(request.getCode())
                .wabaId(request.getWabaId())
                .businessManagerId(request.getBusinessManagerId())
                .status(OnboardingTask.Status.PENDING)
                .build();
        task = taskRepository.save(task);

        log.info("Onboarding task created: taskId={}, orgId={}", task.getId(), request.getOrganizationId());

        // Trigger async processing — this returns immediately
        processTaskAsync(task.getId(), request);

        return task.getId();
    }

    // ════════════════════════════════════════════════════════════
    // PROCESS — runs on onboardingTaskExecutor thread pool
    // ════════════════════════════════════════════════════════════

    /**
     * Execute the full onboarding flow asynchronously.
     *
     * Called by enqueue() and also by the retry scheduler for failed tasks.
     * Each invocation is independent — failure here does NOT affect the controller.
     */
    @Async("onboardingTaskExecutor")
    public void processTaskAsync(Long taskId, EmbeddedSignupCallbackRequest request) {
        log.info("Onboarding task picked up: taskId={}", taskId);

        // Mark as PROCESSING inside a short transaction
        markProcessing(taskId);

        try {
            EmbeddedSignupResponse result = embeddedSignupService.processSignupCallback(request);

            markCompleted(taskId, result.getWabaAccountId(), result.getSummary());
            log.info("Onboarding task COMPLETED: taskId={}, wabaAccountId={}",
                    taskId, result.getWabaAccountId());

        } catch (Exception ex) {
            markFailed(taskId, ex.getMessage());
            log.error("Onboarding task FAILED: taskId={}, error={}", taskId, ex.getMessage(), ex);
        }
    }

    /**
     * Retry a specific failed task.
     * Called by the token maintenance scheduler for retryable failures.
     */
    @Async("onboardingTaskExecutor")
    public void retryTask(OnboardingTask task) {
        log.info("Retrying onboarding task: taskId={}, attempt={}", task.getId(), task.getRetryCount() + 1);

        EmbeddedSignupCallbackRequest request = EmbeddedSignupCallbackRequest.builder()
                .organizationId(task.getOrganizationId())
                .code(task.getOauthCode())
                .wabaId(task.getWabaId())
                .businessManagerId(task.getBusinessManagerId())
                .build();

        processTaskAsync(task.getId(), request);
    }

    // ════════════════════════════════════════════════════════════
    // QUERY — called from status endpoint
    // ════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public OnboardingTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding task not found: " + taskId));
    }

    // ════════════════════════════════════════════════════════════
    // MAINTENANCE — called by TokenMaintenanceScheduler
    // ════════════════════════════════════════════════════════════

    /**
     * Reset stuck PROCESSING tasks (worker crashed mid-flight) back to PENDING.
     * Called by the maintenance scheduler every 10 minutes.
     */
    @Transactional
    public int resetStuckTasks() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(10);
        List<OnboardingTask> stuckTasks = taskRepository.findStuckTasks(stuckThreshold);

        stuckTasks.forEach(task -> {
            log.warn("Resetting stuck onboarding task: taskId={}, stuckSince={}", task.getId(), task.getStartedAt());
            task.setStatus(OnboardingTask.Status.PENDING);
            task.setStartedAt(null);
            taskRepository.save(task);
        });

        return stuckTasks.size();
    }

    /**
     * Retry all retryable failed tasks.
     * Called by the maintenance scheduler periodically.
     */
    @Transactional
    public int retryFailedTasks() {
        List<OnboardingTask> retryable = taskRepository.findRetryableFailures();
        retryable.forEach(this::retryTask);
        log.info("Queued {} retryable onboarding tasks", retryable.size());
        return retryable.size();
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE — short-lived transaction helpers
    // ════════════════════════════════════════════════════════════

    @Transactional
    public void markProcessing(Long taskId) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.markProcessing();
            taskRepository.save(t);
        });
    }

    @Transactional
    public void markCompleted(Long taskId, Long wabaAccountId, String summary) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.markCompleted(wabaAccountId, summary);
            taskRepository.save(t);
        });
    }

    @Transactional
    public void markFailed(Long taskId, String errorMessage) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.markFailed(errorMessage);
            taskRepository.save(t);
        });
    }
}