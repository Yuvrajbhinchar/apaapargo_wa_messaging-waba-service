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

    /** Find FAILED tasks that are still eligible for retry */
    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.status = 'FAILED' AND t.retryCount < 3")
    List<OnboardingTask> findRetryableFailures();

    /**
     * Find PROCESSING tasks stuck longer than the threshold.
     * These indicate a worker crash mid-flight and should be reset to PENDING.
     */
    @Query("SELECT t FROM OnboardingTask t " +
            "WHERE t.status = 'PROCESSING' AND t.startedAt < :stuckThreshold")
    List<OnboardingTask> findStuckTasks(@Param("stuckThreshold") LocalDateTime stuckThreshold);

    Optional<OnboardingTask> findByIdempotencyKey(String idempotencyKey);

    // OnboardingTaskRepository
    @Query("SELECT t FROM OnboardingTask t WHERE t.status = 'FAILED' AND t.startedAt IS NULL")
    List<OnboardingTask> findNeverStartedFailures();

}