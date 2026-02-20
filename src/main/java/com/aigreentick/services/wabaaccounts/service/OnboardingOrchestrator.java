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

    @Transactional
    public int resetStuckTasks() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(
                OnboardingTask.STUCK_PROCESSING_MINUTES);
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
    // FAILED TASK RETRY (scheduler)
    // ════════════════════════════════════════════════════════════

    @Transactional
    public int retryFailedTasks() {
        List<OnboardingTask> retryable = taskRepository.findRetryableFailures();
        retryable.forEach(dispatcher::redispatch);
        log.info("Queued {} retryable onboarding tasks", retryable.size());
        return retryable.size();
    }
}