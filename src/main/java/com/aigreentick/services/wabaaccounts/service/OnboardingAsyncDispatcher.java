package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.constants.OnboardingStep;
import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import com.aigreentick.services.wabaaccounts.exception.TaskOwnershipLostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingAsyncDispatcher {

    private final EmbeddedSignupService embeddedSignupService;
    private final OnboardingTaskStateService taskStateService;

    /**
     * Dispatch onboarding work to the background thread pool.
     *
     * CONCURRENCY FIX:
     * tryClaimTask() is an atomic UPDATE ... WHERE status = 'PENDING'.
     * If two threads dispatch the same taskId simultaneously, exactly
     * ONE will get updated=1 (claimed), the other gets updated=0 (rejected).
     * The rejected thread exits immediately — no duplicate work.
     *
     * This is the ONLY entry point for task execution. All paths
     * (initial dispatch, scheduler retry, manual retry) flow through here.
     */
    @Async("onboardingTaskExecutor")
    public void dispatch(Long taskId, EmbeddedSignupCallbackRequest request) {
        String threadName = Thread.currentThread().getName();

        boolean claimed = taskStateService.tryClaimTask(taskId);
        if (!claimed) {
            log.info("Task {} already claimed — thread [{}] exiting", taskId, threadName);
            return;
        }

        log.info("Task {} claimed by thread [{}]", taskId, threadName);

        try {
            EmbeddedSignupResponse result =
                    embeddedSignupService.processSignupCallback(request, taskId);

            boolean committed = taskStateService.markCompleted(
                    taskId, result.getWabaAccountId(), result.getSummary());

            if (committed) {
                log.info("Task {} COMPLETED: wabaAccountId={}", taskId, result.getWabaAccountId());
            } else {
                log.warn("Task {} completed but CAS rejected — " +
                                "task already in terminal state (likely user cancelled). " +
                                "WABA {} was created successfully.",
                        taskId, result.getWabaAccountId());
            }

        } catch (TaskOwnershipLostException ex) {
            // ╔════════════════════════════════════════════════════════╗
            // ║  GAP 2 FIX: Worker detected it lost ownership.       ║
            // ║  Task was cancelled/reset by user or scheduler.      ║
            // ║  Do NOT call markFailed — the task is already in     ║
            // ║  its intended state (CANCELLED, PENDING, etc).       ║
            // ╚════════════════════════════════════════════════════════╝
            log.info("Task {} — worker aborting: ownership lost (status={}). " +
                            "Task was cancelled or reset externally.",
                    ex.getTaskId(), ex.getDetectedStatus());
            // Intentionally no markFailed() — respect the external state change

        } catch (Exception ex) {
            boolean committed = taskStateService.markFailed(taskId, ex.getMessage());
            if (committed) {
                log.error("Task {} FAILED: {}", taskId, ex.getMessage(), ex);
            } else {
                // Task already COMPLETED by another path — that's fine
                log.warn("Task {} exception but failure CAS rejected — " +
                                "task already completed or cancelled. Error was: {}",
                        taskId, ex.getMessage());
            }
        }
    }
    /**
     * Re-dispatch a failed task for retry.
     *
     * The task must be in PENDING state (reset by the scheduler) before
     * this is called. The tryClaimTask() inside dispatch() ensures only
     * one thread processes it.
     *
     * KEY CHANGE: We no longer refuse retry when startedAt != null.
     * The saga resumes from the last completed step. The only unrecoverable
     * case is when the code was consumed but the token was never persisted
     * (crash in the ~5ms between Meta response and DB commit).
     */
    @Async("onboardingTaskExecutor")
    public void redispatch(OnboardingTask task) {
        log.info("Retry requested: taskId={}, attempt={}, completedSteps={}",
                task.getId(), task.getRetryCount() + 1, task.getCompletedSteps());

        // Check for the narrow unrecoverable window:
        // Code was consumed (task reached PROCESSING before) but token was never saved
        if (task.getStartedAt() != null
                && task.getEncryptedAccessToken() == null
                && !task.isStepCompleted(OnboardingStep.TOKEN_EXCHANGE)) {

            String msg = "OAuth code consumed but token was never persisted " +
                    "(crash between Meta call and DB write). " +
                    "Customer must restart embedded signup for a fresh code.";
            log.warn("Unrecoverable task: taskId={} — {}", task.getId(), msg);
            taskStateService.markFailed(task.getId(), msg);
            return;
        }

        // Reconstruct request with resolved values from previous attempt
        EmbeddedSignupCallbackRequest request = EmbeddedSignupCallbackRequest.builder()
                .organizationId(task.getOrganizationId())
                .code(task.getOauthCode())
                .wabaId(task.getResolvedWabaId() != null
                        ? task.getResolvedWabaId() : task.getWabaId())
                .businessManagerId(task.getResolvedBusinessManagerId() != null
                        ? task.getResolvedBusinessManagerId() : task.getBusinessManagerId())
                .phoneNumberId(task.getResolvedPhoneNumberId() != null
                        ? task.getResolvedPhoneNumberId() : task.getPhoneNumberId())
                .signupType(task.getSignupType())
                .build();

        // dispatch() will call tryClaimTask() — safe against concurrent retries
        dispatch(task.getId(), request);
    }
}