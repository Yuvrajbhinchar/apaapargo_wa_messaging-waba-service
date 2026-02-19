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
import java.util.Optional;

/**
 * ══════════════════════════════════════════════════════════════════
 * Onboarding Orchestrator — Task Coordination
 * ══════════════════════════════════════════════════════════════════
 *
 * BLOCKER 2 FIX — Spring AOP Self-Invocation
 * ───────────────────────────────────────────
 * Original bug: enqueue() called processTaskAsync() on THIS bean (self-invocation).
 * Spring's @Async proxy is bypassed. Method ran synchronously, blocking the
 * HTTP thread for the full 8-12 second Meta API flow.
 *
 * Fix applied:
 *   - Removed ALL @Async methods from this class
 *   - @Async lives in OnboardingAsyncDispatcher (separate bean)
 *   - enqueue() calls dispatcher.dispatch() — cross-bean call, proxy honoured
 *   - retryFailedTasks() calls dispatcher.redispatch() — same fix
 *
 *   - Removed mark* methods from this class
 *   - mark* methods live in OnboardingTaskStateService (separate bean)
 *   - Needed to avoid circular dependency: Orchestrator→Dispatcher→Orchestrator
 *
 * This class now has ONE responsibility: task lifecycle coordination (create,
 * query, reset stuck tasks, trigger retries). No @Async. No mark* DB writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingOrchestrator {

    private final OnboardingTaskRepository taskRepository;
    private final EmbeddedSignupService embeddedSignupService;
    private final OnboardingTaskStateService taskStateService; // ← injected, never `this`

    // enqueue() is unchanged — shown for context only
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
                    .idempotencyKey(idempotencyKey)
                    .status(OnboardingTask.Status.PENDING)
                    .build();

            task = taskRepository.saveAndFlush(task);
            log.info("Onboarding task created: taskId={}, orgId={}",
                    task.getId(), request.getOrganizationId());

            processTaskAsync(task.getId(), request);
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

    @Async("onboardingTaskExecutor")
    public void processTaskAsync(Long taskId, EmbeddedSignupCallbackRequest request) {
        log.info("Onboarding task picked up: taskId={}", taskId);

        // Calls external bean → proxy is invoked → @Transactional honoured
        taskStateService.markProcessing(taskId);

        try {
            EmbeddedSignupResponse result =
                    embeddedSignupService.processSignupCallback(request);

            taskStateService.markCompleted(taskId,
                    result.getWabaAccountId(), result.getSummary());
            log.info("Onboarding task COMPLETED: taskId={}, wabaAccountId={}",
                    taskId, result.getWabaAccountId());

        } catch (Exception ex) {
            taskStateService.markFailed(taskId, ex.getMessage());
            log.error("Onboarding task FAILED: taskId={}, error={}",
                    taskId, ex.getMessage(), ex);
        }
    }

    @Async("onboardingTaskExecutor")
    public void retryTask(OnboardingTask task) {
        log.info("Retrying onboarding task: taskId={}, attempt={}",
                task.getId(), task.getRetryCount() + 1);

        EmbeddedSignupCallbackRequest request = EmbeddedSignupCallbackRequest.builder()
                .organizationId(task.getOrganizationId())
                .code(task.getOauthCode())
                .wabaId(task.getWabaId())
                .businessManagerId(task.getBusinessManagerId())
                .build();

        processTaskAsync(task.getId(), request);
    }

    @Transactional(readOnly = true)
    public OnboardingTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Onboarding task not found: " + taskId));
    }

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

    @Transactional
    public int retryFailedTasks() {
        List<OnboardingTask> retryable = taskRepository.findRetryableFailures();
        retryable.forEach(this::retryTask);
        log.info("Queued {} retryable onboarding tasks", retryable.size());
        return retryable.size();
    }

    // markProcessing(), markCompleted(), markFailed() DELETED —
    // moved to OnboardingTaskStateService where @Transactional is proxy-enforced.
}