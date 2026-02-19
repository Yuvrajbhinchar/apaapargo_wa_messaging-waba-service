package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.repository.OnboardingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns all transactional state mutations for OnboardingTask.
 *
 * WHY THIS CLASS EXISTS
 * ──────────────────────
 * Spring @Transactional works via CGLIB proxy interception. A proxy is only
 * invoked when the call comes from *outside* the bean — i.e. through the
 * injected reference, not through `this`.
 *
 * OnboardingOrchestrator.processTaskAsync() previously called its own
 * markProcessing() / markCompleted() / markFailed() methods directly
 * (self-invocation via `this`), bypassing the proxy entirely. Every
 * status update therefore ran without a transaction, meaning:
 *   - No atomicity guarantee — partial column updates possible on crash
 *   - No rollback if the save itself failed
 *   - Hibernate dirty-checking may or may not flush, non-deterministically
 *
 * Moving these mutations here means OnboardingOrchestrator injects this
 * bean and calls it through Spring's proxy — @Transactional is honoured.
 *
 * PROPAGATION
 * ────────────
 * All methods use REQUIRES_NEW so each status update commits independently.
 * This is intentional: markProcessing() must commit before the long-running
 * Meta API work starts, and markFailed() must commit even if the caller's
 * surrounding context is rolling back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingTaskStateService {

    private final OnboardingTaskRepository taskRepository;

    /**
     * Transition task to PROCESSING and record the start timestamp.
     * Commits immediately (REQUIRES_NEW) so the status is visible to
     * the polling endpoint before Meta API work begins.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markProcessing(Long taskId) {
        OnboardingTask task = findOrThrow(taskId);
        task.markProcessing();
        taskRepository.save(task);
        log.debug("Task {} → PROCESSING", taskId);
    }

    /**
     * Transition task to COMPLETED, recording the WABA account ID and summary.
     * Commits immediately (REQUIRES_NEW).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markCompleted(Long taskId, Long wabaAccountId, String summary) {
        OnboardingTask task = findOrThrow(taskId);
        task.markCompleted(wabaAccountId, summary);
        taskRepository.save(task);
        log.debug("Task {} → COMPLETED (wabaAccountId={})", taskId, wabaAccountId);
    }

    /**
     * Transition task to FAILED, recording the error and incrementing retryCount.
     * Commits immediately (REQUIRES_NEW) — this must persist even when the
     * caller's transaction is rolling back due to the very error being recorded.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String errorMessage) {
        OnboardingTask task = findOrThrow(taskId);
        task.markFailed(errorMessage);
        taskRepository.save(task);
        log.debug("Task {} → FAILED (attempt {})", taskId, task.getRetryCount());
    }

    // ─────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────

    private OnboardingTask findOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "OnboardingTask not found: " + taskId));
    }
}