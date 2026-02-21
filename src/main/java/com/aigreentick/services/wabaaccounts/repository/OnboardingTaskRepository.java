package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.entity.OnboardingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, Long> {

    // ── Existing finders (unchanged) ──────────────────────────

    Optional<OnboardingTask> findByIdempotencyKey(String idempotencyKey);

    Optional<OnboardingTask> findByOrganizationIdAndStatus(Long organizationId,
                                                           OnboardingTask.Status status);

    List<OnboardingTask> findByStatus(OnboardingTask.Status status);

    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.organizationId = :orgId " +
            "AND t.status IN ('PENDING', 'PROCESSING') " +
            "ORDER BY t.createdAt DESC")
    List<OnboardingTask> findActiveTasksForOrg(@Param("orgId") Long orgId);

    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.organizationId = :orgId " +
            "AND t.status = 'FAILED' " +
            "ORDER BY t.updatedAt DESC")
    List<OnboardingTask> findFailedTasksForOrg(@Param("orgId") Long orgId);

    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.status = 'FAILED' AND t.retryCount < 3 AND t.startedAt IS NULL")
    List<OnboardingTask> findRetryableFailures();

    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.status = 'PROCESSING' AND t.startedAt < :stuckThreshold")
    List<OnboardingTask> findStuckTasks(@Param("stuckThreshold") LocalDateTime stuckThreshold);

    @Query("SELECT t FROM OnboardingTask t WHERE t.status = 'FAILED' AND t.startedAt IS NULL")
    List<OnboardingTask> findNeverStartedFailures();

    // ── Atomic CAS operations ─────────────────────────────────

    /** Claim: PENDING → PROCESSING. Returns 1 if this thread won. */
    @Modifying
    @Query("UPDATE OnboardingTask t " +
            "SET t.status = 'PROCESSING', t.startedAt = :now " +
            "WHERE t.id = :taskId AND t.status = 'PENDING'")
    int claimTaskForProcessing(@Param("taskId") Long taskId,
                               @Param("now") LocalDateTime now);

    /** Complete: (PROCESSING|FAILED) → COMPLETED. Success dominates. */
    @Modifying
    @Query("UPDATE OnboardingTask t " +
            "SET t.status = 'COMPLETED', " +
            "    t.resultWabaAccountId = :wabaAccountId, " +
            "    t.resultSummary = :summary, " +
            "    t.finishedAt = :now, " +
            "    t.errorMessage = NULL " +
            "WHERE t.id = :taskId AND t.status IN ('PROCESSING', 'FAILED')")
    int completeTask(@Param("taskId") Long taskId,
                     @Param("wabaAccountId") Long wabaAccountId,
                     @Param("summary") String summary,
                     @Param("now") LocalDateTime now);

    /** Fail: PROCESSING → FAILED only. Cannot overwrite COMPLETED. */
    @Modifying
    @Query("UPDATE OnboardingTask t " +
            "SET t.status = 'FAILED', " +
            "    t.errorMessage = :error, " +
            "    t.finishedAt = :now, " +
            "    t.retryCount = t.retryCount + 1 " +
            "WHERE t.id = :taskId AND t.status = 'PROCESSING'")
    int failTask(@Param("taskId") Long taskId,
                 @Param("error") String error,
                 @Param("now") LocalDateTime now);

    /** Reset stuck: PROCESSING → PENDING. Atomic per-task. */
    @Modifying
    @Query("UPDATE OnboardingTask t " +
            "SET t.status = 'PENDING', t.startedAt = NULL " +
            "WHERE t.id = :taskId AND t.status = 'PROCESSING'")
    int resetStuckTask(@Param("taskId") Long taskId);

    /** Retry claim: FAILED → PENDING. Atomic, prevents duplicate retries. */
    @Modifying
    @Query("UPDATE OnboardingTask t " +
            "SET t.status = 'PENDING', t.startedAt = NULL " +
            "WHERE t.id = :taskId AND t.status = 'FAILED' AND t.retryCount < :maxRetries")
    int claimTaskForRetry(@Param("taskId") Long taskId,
                          @Param("maxRetries") int maxRetries);
}
