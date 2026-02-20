package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.dto.request.EmbeddedSignupCallbackRequest;
import com.aigreentick.services.wabaaccounts.dto.response.EmbeddedSignupResponse;
import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * ══════════════════════════════════════════════════════════════════
 * Onboarding Async Dispatcher
 * ══════════════════════════════════════════════════════════════════
 *
 * @Async lives HERE. OnboardingOrchestrator calls dispatcher.dispatch() —
 * a cross-bean call. Spring proxy intercepts it. @Async is honoured.
 * HTTP thread returns < 100ms with the task ID.
 *
 * ═══ OAuth Retry Safety ═══════════════════════════════════════════
 * Meta OAuth codes are SINGLE-USE. Once exchanged for a token in Step A
 * of processSignupCallback(), the code is consumed and cannot be reused.
 *
 * If the task previously reached PROCESSING state (startedAt != null),
 * the code was very likely consumed. Retrying will hit Meta with an
 * already-used code → "This authorization code has been used" error.
 *
 * Guard: redispatch() checks if the code was likely consumed.
 * If so, marks the task as permanently failed with a clear message
 * instead of wasting 3 retry attempts that are guaranteed to fail.
 *
 * The user must restart the embedded signup flow to get a fresh code.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingAsyncDispatcher {

    private final EmbeddedSignupService embeddedSignupService;
    private final OnboardingTaskStateService taskStateService;

    /**
     * Dispatch onboarding work to the background thread pool.
     *
     * Called by OnboardingOrchestrator — cross-bean call ensures
     * the Spring proxy intercepts it and @Async is honoured.
     *
     * HOW TO VERIFY THE FIX IS WORKING:
     *   Check logs after a signup. You must see:
     *     "Onboarding task picked up on thread [waba-onboarding-1]"
     *   If you see "http-nio-8081-exec-*" — self-invocation is back.
     */

    @Async("onboardingTaskExecutor")
    public void dispatch(Long taskId, EmbeddedSignupCallbackRequest request) {
        log.info("Onboarding task picked up on thread [{}]: taskId={}",
                Thread.currentThread().getName(), taskId);

        taskStateService.markProcessing(taskId);

        try {
            EmbeddedSignupResponse result = embeddedSignupService.processSignupCallback(request);
            taskStateService.markCompleted(taskId, result.getWabaAccountId(), result.getSummary());
            log.info("Onboarding task COMPLETED: taskId={}, wabaAccountId={}",
                    taskId, result.getWabaAccountId());
        } catch (Exception ex) {
            taskStateService.markFailed(taskId, ex.getMessage());
            log.error("Onboarding task FAILED: taskId={}, error={}", taskId, ex.getMessage(), ex);
        }
    }

    /**
     * Re-dispatch a failed task for retry.
     *
     * ═══ OAuth Retry Safety Guard ═══
     *
     * OAuth codes are single-use. If this task previously reached PROCESSING
     * (startedAt != null), then dispatch() was called before, which called
     * processSignupCallback() → exchangeCodeForToken(). Even if the
     * subsequent steps failed, the code is already consumed by Meta.
     *
     * Retrying with the same code is GUARANTEED to fail:
     *   Meta returns: "This authorization code has been used"
     *
     * Instead of wasting 3 retry attempts on a dead code, we immediately
     * mark the task as permanently failed with a clear message telling
     * the customer to restart the signup flow.
     *
     * When IS a retry useful?
     *   - Task failed BEFORE reaching PROCESSING (e.g., DB error on save)
     *     → startedAt is null → code was never sent to Meta → retry is safe
     */

    @Async("onboardingTaskExecutor")
    public void redispatch(OnboardingTask task) {
        log.info("Retry requested for onboarding task: taskId={}, attempt={}, startedAt={}",
                task.getId(), task.getRetryCount() + 1, task.getStartedAt());

        // OAuth codes are single-use — if task previously reached PROCESSING, code is consumed
        if (task.getStartedAt() != null) {
            String msg = "OAuth code already consumed (task previously reached PROCESSING at " +
                    task.getStartedAt() + "). " +
                    "Meta OAuth codes are single-use. Customer must restart embedded signup.";
            log.warn("Skipping retry for taskId={}: {}", task.getId(), msg);
            taskStateService.markFailed(task.getId(), msg);
            return;
        }

        log.info("Retrying onboarding task on thread [{}]: taskId={}, attempt={}",
                Thread.currentThread().getName(), task.getId(), task.getRetryCount() + 1);

        // FIX 4: Reconstruct FULL request including phoneNumberId and signupType
        // Without these, retry loses coexistence context → SMB sync skipped,
        // wrong phone number discovered on re-run
        EmbeddedSignupCallbackRequest request = EmbeddedSignupCallbackRequest.builder()
                .organizationId(task.getOrganizationId())
                .code(task.getOauthCode())
                .wabaId(task.getWabaId())
                .businessManagerId(task.getBusinessManagerId())
                .phoneNumberId(task.getPhoneNumberId())       // FIX 4
                .signupType(task.getSignupType())              // FIX 4
                .build();

        dispatch(task.getId(), request);
    }
}