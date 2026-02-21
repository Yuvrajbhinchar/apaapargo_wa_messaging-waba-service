package com.aigreentick.services.wabaaccounts.entity;

import com.aigreentick.services.wabaaccounts.constants.OnboardingStep;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(
        name = "onboarding_tasks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_onboarding_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_onboarding_org",        columnList = "organization_id"),
                @Index(name = "idx_onboarding_status",     columnList = "status"),
                @Index(name = "idx_onboarding_org_status", columnList = "organization_id, status"),
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingTask {

    /**
     * How long a PROCESSING task must be running before it is considered
     * stuck and eligible for customer-initiated cancellation.
     *
     * 10 minutes covers:
     *   - Normal flow: 8–12s peak. Stuck tasks are obvious long before 10m.
     *   - Meta API hanging: WebClient has a 30s timeout, so 10m is safe margin.
     *   - Deployment restart: Spring waits 60s for tasks on shutdown; if it was
     *     killed hard, the task is stuck immediately. 10m gives the scheduler
     *     time to reset it first (resetStuckTasks() runs every 5m). Only if
     *     the scheduler also fails does the customer need self-service.
     */
    public static final int STUCK_PROCESSING_MINUTES = 15;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "oauth_code", nullable = false, length = 500)
    private String oauthCode;

    @Column(name = "waba_id", length = 100)
    private String wabaId;

    @Column(name = "business_manager_id", length = 100)
    private String businessManagerId;

    @Column(name = "phone_number_id", length = 100)
    private String phoneNumberId;

    @Column(name = "signup_type", length = 50)
    private String signupType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "result_waba_account_id")
    private Long resultWabaAccountId;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;


    @Column(name = "completed_steps", columnDefinition = "TEXT")
    private String completedSteps;


    @Column(name = "encrypted_access_token", columnDefinition = "TEXT")
    private String encryptedAccessToken;

    /** Token expiry in seconds — persisted alongside token */
    @Column(name = "token_expires_in")
    private Long tokenExpiresIn;

    /** Resolved WABA ID — may differ from initial request */
    @Column(name = "resolved_waba_id", length = 100)
    private String resolvedWabaId;

    /** Resolved Business Manager ID */
    @Column(name = "resolved_bm_id", length = 100)
    private String resolvedBusinessManagerId;

    /** Resolved phone number ID (auto-discovered or from request) */
    @Column(name = "resolved_phone_number_id", length = 100)
    private String resolvedPhoneNumberId;

    /** DB ID of the created WabaAccount — set after Step G */
    // (resultWabaAccountId already exists — reuse it)

    // ── Step tracking methods ──────────────────────────────────

    public boolean isStepCompleted(OnboardingStep step) {
        if (completedSteps == null || completedSteps.isBlank()) return false;
        return Set.of(completedSteps.split(",")).contains(step.name());
    }

    public void markStepCompleted(OnboardingStep step) {
        if (completedSteps == null || completedSteps.isBlank()) {
            completedSteps = step.name();
        } else if (!isStepCompleted(step)) {
            completedSteps = completedSteps + "," + step.name();
        }
    }

    public Set<OnboardingStep> getCompletedStepSet() {
        if (completedSteps == null || completedSteps.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(completedSteps.split(","))
                .map(OnboardingStep::valueOf)
                .collect(Collectors.toSet());
    }

    // ════════════════════════════════════════════════════════════
    // STATUS ENUM
    // ════════════════════════════════════════════════════════════

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // ════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ════════════════════════════════════════════════════════════

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

    public void markCancelled(String reason) {
        this.status = Status.CANCELLED;
        this.errorMessage = "Cancelled: " + reason;
        this.finishedAt = LocalDateTime.now();
    }

    // ════════════════════════════════════════════════════════════
    // STATE QUERIES
    // ════════════════════════════════════════════════════════════

    /**
     * EDGE CASE 1 FIX: PROCESSING tasks older than STUCK_PROCESSING_MINUTES
     * are cancellable, not just FAILED.
     *
     * Without this, a stuck PROCESSING task leaves the customer with no
     * self-service exit. The scheduler resets truly stuck tasks automatically,
     * but if the scheduler itself has issues, the customer needs a way out.
     *
     * Decision table:
     *   PENDING           → false  (just wait, it hasn't started yet)
     *   PROCESSING fresh  → false  (worker is running, do not interrupt)
     *   PROCESSING stuck  → true   (older than threshold, safe to cancel)
     *   FAILED            → true   (always cancellable)
     *   COMPLETED         → false  (terminal success, nothing to cancel)
     *   CANCELLED         → false  (already done — idempotency handled separately)
     */
    public boolean isCancellable() {
        if (status == Status.FAILED) return true;
        if (status == Status.PROCESSING) return isStaleProcessing();
        return false;
    }

    /**
     * EDGE CASE 2 FIX: Already-cancelled tasks return true so the controller
     * can treat them as an idempotent no-op rather than throwing an error.
     *
     * This is separate from isCancellable() to keep the semantics clean:
     *   isCancellable()  = "can we transition to CANCELLED now?"
     *   isAlreadyCancelled() = "is it already there?"
     */
    public boolean isAlreadyCancelled() {
        return status == Status.CANCELLED;
    }

    /** True if PROCESSING for longer than STUCK_PROCESSING_MINUTES. */
    public boolean isStaleProcessing() {
        if (status != Status.PROCESSING || startedAt == null) return false;
        return startedAt.plusMinutes(STUCK_PROCESSING_MINUTES).isBefore(LocalDateTime.now());
    }

    /** Used by the scheduler's resetStuckTasks() — same threshold. */
    public boolean isStuck() {
        return isStaleProcessing();
    }

    /** Only FAILED tasks with retryCount < 3 are eligible for automatic retry. */
    public boolean isRetryable() {
        return status == Status.FAILED && retryCount < 3;
    }

    /**
     * EDGE CASE 3 FIX: "Active" means a worker is running or will run.
     * FAILED requires user action first — it is NOT active.
     *
     * Used by findActiveTasksForOrg() to decide what the frontend should poll.
     *
     *   PENDING     → active (queued, worker will pick it up)
     *   PROCESSING  → active (worker is running)
     *   FAILED      → NOT active (needs user action: cancel + restart)
     *   CANCELLED   → NOT active (terminal)
     *   COMPLETED   → NOT active (terminal)
     */
    public boolean isActive() {
        return status == Status.PENDING || status == Status.PROCESSING;
    }
}