package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.constants.OnboardingStep;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.OnboardingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingTaskStateService {

    private final OnboardingTaskRepository taskRepository;

    // ════════════════════════════════════════════════════════════
    // CLAIM
    // ════════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimTask(Long taskId) {
        int updated = taskRepository.claimTaskForProcessing(taskId, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Task {} claim FAILED — already claimed by another worker", taskId);
            return false;
        }
        log.info("Task {} claimed by thread [{}]", taskId, Thread.currentThread().getName());
        return true;
    }

    // ════════════════════════════════════════════════════════════
    // TERMINAL TRANSITIONS — asymmetric CAS (GAP 1 FIX)
    // ════════════════════════════════════════════════════════════

    /**
     * Mark completed. Wins over both PROCESSING and FAILED.
     * Only loses to CANCELLED (user explicitly cancelled — respect that).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markCompleted(Long taskId, Long wabaAccountId, String summary) {
        int updated = taskRepository.completeTask(
                taskId, wabaAccountId, summary, LocalDateTime.now());

        if (updated == 0) {
            // Task is COMPLETED (idempotent) or CANCELLED (user chose to cancel).
            // Either way, this worker's result is discarded — which is fine.
            OnboardingTask current = findOrThrow(taskId);
            log.warn("Task {} completion CAS returned 0 — current status: {}",
                    taskId, current.getStatus());
            return false;
        }

        log.info("Task {} → COMPLETED (wabaAccountId={})", taskId, wabaAccountId);
        return true;
    }

    /**
     * Mark failed. Only overwrites PROCESSING.
     * Cannot overwrite COMPLETED (GAP 1 FIX) or CANCELLED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long taskId, String errorMessage) {
        int updated = taskRepository.failTask(taskId, errorMessage, LocalDateTime.now());

        if (updated == 0) {
            OnboardingTask current = findOrThrow(taskId);
            log.warn("Task {} failure CAS returned 0 — current status: {} " +
                            "(task likely completed or cancelled by another path)",
                    taskId, current.getStatus());
            return false;
        }

        log.info("Task {} → FAILED: {}", taskId, errorMessage);
        return true;
    }

    /**
     * Cancel. Handles idempotency and state checks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OnboardingTask markCancelled(Long taskId, String reason) {
        OnboardingTask task = findOrThrow(taskId);

        if (task.isAlreadyCancelled()) {
            log.debug("Task {} already CANCELLED — no-op", taskId);
            return task;
        }

        if (!task.isCancellable()) {
            String detail = task.getStatus() == OnboardingTask.Status.PROCESSING
                    ? "Task is actively processing (started " + task.getStartedAt() +
                    "). Wait " + OnboardingTask.STUCK_PROCESSING_MINUTES + " min."
                    : "Cannot cancel task in status: " + task.getStatus();
            throw new IllegalStateException(detail);
        }

        task.markCancelled(reason);
        OnboardingTask saved = taskRepository.save(task);
        log.info("Task {} → CANCELLED. Reason: {}", taskId, reason);
        return saved;
    }

    // ════════════════════════════════════════════════════════════
    // OWNERSHIP CHECK (GAP 2 FIX)
    // ════════════════════════════════════════════════════════════

    /**
     * Check if this worker still owns the task.
     * Returns true only if status is still PROCESSING.
     */
    @Transactional(readOnly = true)
    public boolean verifyOwnership(Long taskId) {
        OnboardingTask task = findOrThrow(taskId);
        return task.getStatus() == OnboardingTask.Status.PROCESSING;
    }

    // ════════════════════════════════════════════════════════════
    // STEP PERSISTENCE
    // ════════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistStepResult(Long taskId, OnboardingStep step,
                                  java.util.function.Consumer<OnboardingTask> stateUpdater) {
        OnboardingTask task = findOrThrow(taskId);

        if (task.getStatus() != OnboardingTask.Status.PROCESSING) {
            throw new com.aigreentick.services.wabaaccounts.exception
                    .TaskOwnershipLostException(taskId, task.getStatus().name());
        }

        stateUpdater.accept(task);
        task.markStepCompleted(step);
        taskRepository.save(task);
        log.debug("Task {} — step {} persisted", taskId, step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistStepCompleted(Long taskId, OnboardingStep step) {
        persistStepResult(taskId, step, t -> {});
    }

    @Transactional(readOnly = true)
    public OnboardingTask loadTaskState(Long taskId) {
        return findOrThrow(taskId);
    }

    private OnboardingTask findOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "OnboardingTask not found: " + taskId));
    }
}


