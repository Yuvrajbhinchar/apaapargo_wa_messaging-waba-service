package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.repository.OnboardingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ══════════════════════════════════════════════════════════════════
 * Onboarding Orchestrator — Task Coordination
 * ══════════════════════════════════════════════════════════════════
 *
 * ═══════════════════════════════════════════════════════════════════
 * BLOCKER 3 FIX: Spring AOP Self-Invocation
 * ═══════════════════════════════════════════════════════════════════
 *
 * PROBLEM:
 *   enqueue() called this.processTaskAsync() — self-invocation.
 *   Spring @Async proxy is bypassed on self-calls. Method ran
 *   synchronously on the HTTP thread, blocking it for 8-12 seconds.
 *   Under load, this exhausted the Tomcat thread pool.
 *
 *   Same issue: retryFailedTasks() called this::retryTask — also self-invocation.
 *
 * FIX:
 *   1. ALL @Async methods REMOVED from this class entirely.
 *   2. @Async lives ONLY in OnboardingAsyncDispatcher (separate bean).
 *   3. enqueue() calls dispatcher.dispatch() — cross-bean call → proxy honoured.
 *   4. retryFailedTasks() calls dispatcher.redispatch() — cross-bean → proxy honoured.
 *
 * HOW TO VERIFY THE FIX:
 *   After a signup, check logs. You MUST see:
 *     "Onboarding task picked up on thread [waba-onboarding-1]"
 *   If you see "http-nio-8081-exec-*" — self-invocation is back.
 *
 * DEPENDENCY GRAPH (no cycles):
 *   OnboardingOrchestrator ──────────────► OnboardingAsyncDispatcher
 *          │                                        │
 *          └──────────► OnboardingTaskStateService ◄┘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingOrchestrator {

    private final OnboardingTaskRepository taskRepository;
    private final OnboardingAsyncDispatcher dispatcher;           // ← BLOCKER 3 FIX
    private final OnboardingTaskStateService taskStateService;

    // ════════════════════════════════════════════════════════════
    // ENQUEUE — accepts callback, persists task, dispatches async
    // ════════════════════════════════════════════════════════════

    /**
     * Create an onboarding task and dispatch it to the background thread pool.
     *
     * Returns the task ID in < 100ms. Actual processing happens on the
     * onboardingTaskExecutor thread pool via OnboardingAsyncDispatcher.
     *
     * ═══ BLOCKER 3 FIX ═══
     * OLD BROKEN:
     *   processTaskAsync(task.getId(), request);  // ← this.method() = self-invocation
     *   // @Async silently ignored, ran on HTTP thread for 8-12 seconds
     *
     * NEW FIXED:
     *   dispatcher.dispatch(task.getId(), request);  // ← cross-bean call
     *   // Spring proxy intercepts, @Async honoured, HTTP thread returns immediately
     */
    @Transactional
    public Long enqueue(EmbeddedSignupCallbackRequest request) {
        String idempotencyKey = request.getOrganizationId() + ":" + request.getCode();

        Optional<OnboardingTask> existing =
                taskRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Duplicate embedded signup callback. Returning existing taskId={}",
                    existing.get().getId());
            return existing.get().getId();
        }

        try {
            OnboardingTask task = OnboardingTask.builder()
                    .organizationId(request.getOrganizationId())
                    .oauthCode(request.getCode())
                    .wabaId(request.getWabaId())
                    .businessManagerId(request.getBusinessManagerId())
                    // FIX 4: persist retry context — without these, retry loses coexistence state
                    .phoneNumberId(request.getPhoneNumberId())
                    .signupType(request.getSignupType())
                    .status(OnboardingTask.Status.PENDING)
                    .build();

            task = taskRepository.saveAndFlush(task);
            log.info("Onboarding task created: taskId={}, orgId={}, signupType={}",
                    task.getId(), request.getOrganizationId(), request.getSignupType());

            dispatcher.dispatch(task.getId(), request);
            return task.getId();

        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            log.warn("Race condition creating onboarding task — fetching existing.");
            return taskRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key exists but task not found — DB inconsistency"))
                    .getId();
        }
    }

    // ════════════════════════════════════════════════════════════
    // TASK QUERY
    // ════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public OnboardingTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Onboarding task not found: " + taskId));
    }

    // ════════════════════════════════════════════════════════════
    // STUCK TASK RECOVERY — called by TokenMaintenanceScheduler
    // ════════════════════════════════════════════════════════════


    @Transactional
    public int resetStuckTasks() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(10);
        List<OnboardingTask> stuckTasks = taskRepository.findStuckTasks(stuckThreshold);
        stuckTasks.forEach(task -> {
            log.warn("Resetting stuck task: taskId={}, stuckSince={}",
                    task.getId(), task.getStartedAt());
            task.setStatus(OnboardingTask.Status.PENDING);
            task.setStartedAt(null);
            taskRepository.save(task);
        });
        return stuckTasks.size();
    }

    // ════════════════════════════════════════════════════════════
    // FAILED TASK RETRY — called by TokenMaintenanceScheduler
    // ════════════════════════════════════════════════════════════

    /**
     * Re-queue FAILED onboarding tasks that have retried fewer than 3 times.
     *
     * ═══ BLOCKER 3 FIX ═══
     * OLD BROKEN:
     *   retryable.forEach(this::retryTask);  // self-invocation, @Async ignored
     *
     * NEW FIXED:
     *   retryable.forEach(dispatcher::redispatch);  // cross-bean, @Async honoured
     */
    @Transactional
    public int retryFailedTasks() {
        List<OnboardingTask> retryable = taskRepository.findRetryableFailures();
        retryable.forEach(dispatcher::redispatch);
        log.info("Queued {} retryable onboarding tasks", retryable.size());
        return retryable.size();
    }

    // ════════════════════════════════════════════════════════════
    // REMOVED — processTaskAsync() and retryTask() deleted.
    // @Async methods now live ONLY in OnboardingAsyncDispatcher.
    // This eliminates the self-invocation bug permanently.
    // ════════════════════════════════════════════════════════════
}