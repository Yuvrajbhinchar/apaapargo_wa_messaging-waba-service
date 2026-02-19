package com.aigreentick.services.wabaaccounts.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks the state of an async WhatsApp onboarding job.
 *
 * ─── Why this exists ──────────────────────────────────────────────
 * The embedded signup flow takes 8–12 seconds synchronously (token exchange,
 * extension, scope check, WABA verify, webhook, phone sync, system user).
 *
 * With retries and Meta API latency spikes this can exceed any reasonable
 * frontend HTTP timeout. Frontend gets a 504 and the user sees an error
 * even though onboarding may have succeeded silently.
 *
 * Pattern:
 *   1. POST /callback → validate input → save task → enqueue → return task_id (< 100ms)
 *   2. Worker processes the real flow in background
 *   3. Frontend polls GET /callback/status/{taskId} until COMPLETED or FAILED
 *
 * ─── State machine ────────────────────────────────────────────────
 *   PENDING → PROCESSING → COMPLETED
 *                       ↘ FAILED
 *
 * Retry: if FAILED and retry_count < 3, scheduler requeues automatically.
 */
@Entity
@Table(
        name = "onboarding_tasks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_onboarding_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_onboarding_org", columnList = "organization_id"),
                @Index(name = "idx_onboarding_status", columnList = "status"),
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Short-lived OAuth code from Meta SDK — consumed during processing */
    @Column(name = "oauth_code", nullable = false, length = 500)
    private String oauthCode;

    /** WABA ID provided by the frontend (may be null — auto-discovered if so) */
    @Column(name = "waba_id", length = 100)
    private String wabaId;

    /** Business Manager ID provided by the frontend (may be null) */
    @Column(name = "business_manager_id", length = 100)
    private String businessManagerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    /** Set after COMPLETED — the WABA account created */
    @Column(name = "result_waba_account_id")
    private Long resultWabaAccountId;

    /** Set after COMPLETED — JSON summary for frontend */
    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    /** Set after FAILED — human-readable error */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** How many times we've tried processing this task */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** When processing was started — used for stuck-job detection */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** When processing finished (success or failure) */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public enum Status {
        PENDING,      // Waiting to be picked up by the async worker
        PROCESSING,   // Currently being processed
        COMPLETED,    // Successfully completed
        FAILED        // Failed — see errorMessage. May be retried.
    }

    // ──────────────────────────────────────────────────────────────
    // Convenience mutators
    // ──────────────────────────────────────────────────────────────

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.startedAt = LocalDateTime.now();
    }

    public void markCompleted(Long wabaAccountId, String summary) {
        this.status = Status.COMPLETED;
        this.resultWabaAccountId = wabaAccountId;
        this.resultSummary = summary;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.errorMessage = error;
        this.finishedAt = LocalDateTime.now();
        this.retryCount++;
    }

    public boolean isRetryable() {
        return status == Status.FAILED && retryCount < 3;
    }

    /** True if a PROCESSING task has been stuck for > 5 minutes */
    public boolean isStuck() {
        if (status != Status.PROCESSING || startedAt == null) return false;
        return startedAt.plusMinutes(5).isBefore(LocalDateTime.now());
    }
}