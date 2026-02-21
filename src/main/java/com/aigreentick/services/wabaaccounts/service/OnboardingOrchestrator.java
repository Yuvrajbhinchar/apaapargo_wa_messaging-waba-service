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

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingOrchestrator {

    private final OnboardingTaskRepository taskRepository;
    private final OnboardingAsyncDispatcher dispatcher;
    private final OnboardingTaskStateService taskStateService;
    private static final int MAX_RETRY_COUNT = 3;

    // ════════════════════════════════════════════════════════════
    // ENQUEUE
    // ════════════════════════════════════════════════════════════

    @Transactional
    public Long enqueue(EmbeddedSignupCallbackRequest request) {
        String idempotencyKey = request.getOrganizationId() + ":" + request.getCode();

        Optional<OnboardingTask> existing = taskRepository.findByIdempotencyKey(idempotencyKey);
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
                    .phoneNumberId(request.getPhoneNumberId())
                    .signupType(request.getSignupType())
                    .idempotencyKey(idempotencyKey)
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
    // QUERY
    // ════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public OnboardingTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Onboarding task not found: " + taskId));
    }

    /**
     * EDGE CASE 3: Returns only PENDING / PROCESSING tasks.
     * FAILED is not active — it requires user action.
     */
    @Transactional(readOnly = true)
    public Optional<OnboardingTask> findActiveTaskForOrg(Long organizationId) {
        List<OnboardingTask> tasks = taskRepository.findActiveTasksForOrg(organizationId);
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    /**
     * EDGE CASE 3: Surface last FAILED task for an org.
     * Used by the frontend after a page refresh when there is no active task,
     * to show the user whether their last attempt failed and what to do.
     */
    @Transactional(readOnly = true)
    public Optional<OnboardingTask> findLastFailedTaskForOrg(Long organizationId) {
        List<OnboardingTask> tasks = taskRepository.findFailedTasksForOrg(organizationId);
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    // ════════════════════════════════════════════════════════════
    // CANCEL
    // ════════════════════════════════════════════════════════════

    /**
     * Cancel a task. Ownership-checked here; state machine enforced in state service.
     *
     * EDGE CASE 1: stale PROCESSING tasks are now cancellable (via isCancellable()).
     * EDGE CASE 2: already-CANCELLED tasks return without error (idempotent via state service).
     */
    @Transactional
    public OnboardingTask cancelTask(Long taskId, Long organizationId, String reason) {
        OnboardingTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Onboarding task not found: " + taskId));

        if (!task.getOrganizationId().equals(organizationId)) {
            throw new SecurityException(
                    "Task " + taskId + " does not belong to organization " + organizationId);
        }

        return taskStateService.markCancelled(taskId, reason);
    }

    // ════════════════════════════════════════════════════════════
    // STUCK TASK RECOVERY (scheduler)
    // ════════════════════════════════════════════════════════════

    /**
     * Retry failed onboarding tasks — fully atomic.
     *
     * GAP 4 FIX:
     * Old code did read-then-write (findRetryableFailures → setStatus → save).
     * Two scheduler instances running simultaneously could both read the same
     * FAILED task and both reset it to PENDING → duplicate redispatch calls.
     *
     * The tryClaimTask() in dispatch() would prevent duplicate EXECUTION,
     * but duplicate dispatches waste thread pool capacity and create
     * confusing log noise.
     *
     * Fix: Use atomic CAS query (claimTaskForRetry) that transitions
     * FAILED → PENDING only if the task is still FAILED. Second scheduler
     * instance sees the task as PENDING → claimTaskForRetry returns 0.
     */
    @Transactional
    public int retryFailedTasks() {
        // Step 1: Find candidates (read-only, no state change)
        List<OnboardingTask> candidates = taskRepository.findRetryableFailures();

        if (candidates.isEmpty()) return 0;

        int retried = 0;
        for (OnboardingTask task : candidates) {
            // Step 2: Atomic claim — FAILED → PENDING
            // Only one scheduler instance wins per task
            int updated = taskRepository.claimTaskForRetry(task.getId(), MAX_RETRY_COUNT);

            if (updated == 0) {
                // Another scheduler instance already claimed this task,
                // or retryCount exceeded max between query and now
                log.debug("Task {} retry claim failed — already claimed or max retries exceeded",
                        task.getId());
                continue;
            }

            log.info("Task {} claimed for retry (attempt {})", task.getId(), task.getRetryCount() + 1);

            // Step 3: Reload fresh state and dispatch
            // Must reload because the entity in `candidates` list is stale
            // (status was FAILED, now it's PENDING after the atomic update)
            OnboardingTask fresh = taskRepository.findById(task.getId()).orElse(null);
            if (fresh != null) {
                dispatcher.redispatch(fresh);
                retried++;
            }
        }

        if (retried > 0) {
            log.info("Queued {} failed onboarding tasks for retry", retried);
        }
        return retried;
    }

    /**
     * Reset stuck tasks — also atomic per GAP 4 principle.
     * (Already fixed in previous artifact, included here for completeness)
     */
    @Transactional
    public int resetStuckTasks() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(
                OnboardingTask.STUCK_PROCESSING_MINUTES);
        List<OnboardingTask> stuckTasks = taskRepository.findStuckTasks(stuckThreshold);

        int resetCount = 0;
        for (OnboardingTask task : stuckTasks) {
            int updated = taskRepository.resetStuckTask(task.getId());
            if (updated > 0) {
                log.warn("Reset stuck task: taskId={}, stuckSince={}",
                        task.getId(), task.getStartedAt());
                resetCount++;
            }
        }
        return resetCount;
    }


}