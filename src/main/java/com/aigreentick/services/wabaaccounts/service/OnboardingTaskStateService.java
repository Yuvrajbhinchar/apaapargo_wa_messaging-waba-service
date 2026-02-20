package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.OnboardingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingTaskStateService {

    private final OnboardingTaskRepository taskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(Long taskId) {
        OnboardingTask task = findOrThrow(taskId);
        task.markProcessing();
        taskRepository.save(task);
        log.debug("Task {} → PROCESSING", taskId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Long taskId, Long wabaAccountId, String summary) {
        OnboardingTask task = findOrThrow(taskId);
        task.markCompleted(wabaAccountId, summary);
        taskRepository.save(task);
        log.debug("Task {} → COMPLETED (wabaAccountId={})", taskId, wabaAccountId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String errorMessage) {
        OnboardingTask task = findOrThrow(taskId);
        task.markFailed(errorMessage);
        taskRepository.save(task);
        log.debug("Task {} → FAILED (attempt {})", taskId, task.getRetryCount());
    }

    /**
     * Cancel a task. Three outcomes:
     *
     *   1. Already CANCELLED → return it as-is (idempotent, no DB write needed).
     *      EDGE CASE 2 FIX: double-cancel is safe, not an error.
     *
     *   2. FAILED or stale PROCESSING → transition to CANCELLED.
     *      EDGE CASE 1 FIX: stale PROCESSING is now cancellable, not just FAILED.
     *
     *   3. Anything else (fresh PROCESSING, PENDING, COMPLETED) → throw.
     *      These states must not be cancelled:
     *        - PENDING/fresh PROCESSING: a worker is about to run or is running.
     *          Cancelling here would leave the worker running against a cancelled
     *          task — it would still call Meta APIs and the result would be lost.
     *        - COMPLETED: customer already connected successfully; nothing to cancel.
     *
     * @return the (possibly unchanged) task in CANCELLED state
     * @throws IllegalStateException if the task is not cancellable
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OnboardingTask markCancelled(Long taskId, String reason) {
        OnboardingTask task = findOrThrow(taskId);

        // EDGE CASE 2: idempotent — already cancelled is fine, return immediately
        if (task.isAlreadyCancelled()) {
            log.debug("Task {} already CANCELLED — idempotent no-op", taskId);
            return task;
        }

        // EDGE CASE 1 + original: FAILED always cancellable; stale PROCESSING also cancellable
        if (!task.isCancellable()) {
            String detail = task.getStatus() == OnboardingTask.Status.PROCESSING
                    ? "Task is still actively processing (started at " + task.getStartedAt() +
                    "). Wait " + OnboardingTask.STUCK_PROCESSING_MINUTES + " minutes or let the scheduler reset it."
                    : "Only FAILED or stale PROCESSING tasks can be cancelled. Current status: " + task.getStatus();
            throw new IllegalStateException(detail);
        }

        task.markCancelled(reason);
        OnboardingTask saved = taskRepository.save(task);
        log.info("Task {} → CANCELLED (was {}). Reason: {}", taskId, task.getStatus(), reason);
        return saved;
    }

    // ─────────────────────────────────────────────────────────────

    private OnboardingTask findOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "OnboardingTask not found: " + taskId));
    }
}