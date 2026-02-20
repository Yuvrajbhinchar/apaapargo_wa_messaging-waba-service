package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, Long> {

    Optional<OnboardingTask> findByOrganizationIdAndStatus(Long organizationId,
                                                           OnboardingTask.Status status);

    List<OnboardingTask> findByStatus(OnboardingTask.Status status);

    /**
     * EDGE CASE 3 FIX: "Active" means a worker is running or queued to run.
     * FAILED is excluded — it requires user action (cancel + restart) and
     * should not be surfaced as a task the frontend should poll.
     *
     * BEFORE (wrong):
     *   WHERE status NOT IN ('CANCELLED', 'COMPLETED')
     *   → included FAILED, which is a terminal-from-UX-perspective state
     *
     * AFTER (correct):
     *   WHERE status IN ('PENDING', 'PROCESSING')
     *   → only truly in-flight tasks
     *
     * Frontend behaviour per status:
     *   PENDING     → show spinner "Connecting..."
     *   PROCESSING  → show spinner "Connecting..."
     *   FAILED      → GET /status shows action_required — frontend handles separately
     *   CANCELLED   → no active task (customer is restarting)
     *   COMPLETED   → no active task (redirect to dashboard)
     */
    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.organizationId = :orgId " +
            "AND t.status IN ('PENDING', 'PROCESSING') " +
            "ORDER BY t.createdAt DESC")
    List<OnboardingTask> findActiveTasksForOrg(@Param("orgId") Long orgId);

    /**
     * Find the most recent FAILED task for an org.
     * Used by the frontend to display the last failure and its action_required
     * when there is no active task (user may need to cancel and restart).
     */
    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.organizationId = :orgId " +
            "AND t.status = 'FAILED' " +
            "ORDER BY t.updatedAt DESC")
    List<OnboardingTask> findFailedTasksForOrg(@Param("orgId") Long orgId);

    // (only tasks whose code was never sent to Meta)
     @Query("SELECT t FROM OnboardingTask t " +
     "WHERE t.status = 'FAILED' AND t.retryCount < 3 AND t.startedAt IS NULL")
     List<OnboardingTask> findRetryableFailures();


     @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.status = 'PROCESSING' AND t.startedAt < :stuckThreshold")
    List<OnboardingTask> findStuckTasks(@Param("stuckThreshold") LocalDateTime stuckThreshold);

    Optional<OnboardingTask> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT t FROM OnboardingTask t WHERE t.status = 'FAILED' AND t.startedAt IS NULL")
    List<OnboardingTask> findNeverStartedFailures();
}