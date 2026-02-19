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
 * WHY THIS CLASS EXISTS — BLOCKER 2: Spring AOP Self-Invocation
 * ──────────────────────────────────────────────────────────────
 * Spring @Async works via AOP proxy. When bean A calls a method on
 * itself (this.method()), it bypasses the proxy. @Async is silently
 * ignored and the method runs on the calling thread.
 *
 * The original OnboardingOrchestrator.enqueue() did exactly this:
 *
 *   processTaskAsync(task.getId(), request);  // self-invocation → @Async ignored
 *   return task.getId();                       // "returned immediately" but blocked 8-12s
 *
 * Proof: thread names in logs showed "http-nio-8080-exec-N" instead of
 * "waba-onboarding-N". Every signup blocked the HTTP thread.
 *
 * THE FIX
 * ────────
 * @Async lives HERE. OnboardingOrchestrator calls dispatcher.dispatch() —
 * a cross-bean call. Spring proxy intercepts it. @Async is honoured.
 * HTTP thread returns < 100ms with the task ID.
 *
 * CIRCULAR DEPENDENCY — SOLVED
 * ─────────────────────────────
 * We cannot inject OnboardingOrchestrator here (that creates a cycle:
 *   Orchestrator → Dispatcher → Orchestrator).
 *
 * Solution: OnboardingTaskStateService owns the mark* DB methods.
 * Both Orchestrator and Dispatcher inject it. No cycle.
 *
 * Acyclic dependency graph:
 *   OnboardingOrchestrator ──────────────► OnboardingAsyncDispatcher
 *          │                                        │
 *          └──────────► OnboardingTaskStateService ◄┘
 *                       (leaf bean, no further deps on these two)
 *
 * NOTE: dispatch() is @Async ONLY — never @Transactional here.
 * @Async switches threads. @Transactional is thread-bound. Putting both
 * on the same method opens and commits the transaction before any real work.
 * DB writes go through OnboardingTaskStateService from the async thread.
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
     *   If you see "http-nio-8080-exec-*" — self-invocation is back.
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
     * OAuth codes are single-use. Retries only help if the code was never
     * consumed (worker died before step A of token exchange). After 3 attempts,
     * the task stays FAILED permanently — user needs a fresh signup.
     */
    @Async("onboardingTaskExecutor")
    public void redispatch(OnboardingTask task) {
        log.info("Retrying onboarding task on thread [{}]: taskId={}, attempt={}",
                Thread.currentThread().getName(), task.getId(), task.getRetryCount() + 1);

        EmbeddedSignupCallbackRequest request = EmbeddedSignupCallbackRequest.builder()
                .organizationId(task.getOrganizationId())
                .code(task.getOauthCode())
                .wabaId(task.getWabaId())
                .businessManagerId(task.getBusinessManagerId())
                .build();

        dispatch(task.getId(), request);
    }
}