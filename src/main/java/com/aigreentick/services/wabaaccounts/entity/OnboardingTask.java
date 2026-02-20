package com.aigreentick.services.wabaaccounts.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

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

    @Column(name = "oauth_code", nullable = false, length = 500)
    private String oauthCode;

    @Column(name = "waba_id", length = 100)
    private String wabaId;

    @Column(name = "business_manager_id", length = 100)
    private String businessManagerId;

    /**
     * FIX 4: Phone number ID from Meta SDK — needed for retry.
     * Without this, coexistence retry loses the phone context and
     * re-discovers a potentially different number.
     */
    @Column(name = "phone_number_id", length = 100)
    private String phoneNumberId;

    /**
     * FIX 4: Signup type from Meta SDK (e.g. "APP_ONBOARDING").
     * Without this, retry doesn't know whether to run coexistence flow
     * (SMB sync) or normal flow → coexistence retry silently skips sync.
     */
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

    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

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

    public boolean isStuck() {
        if (status != Status.PROCESSING || startedAt == null) return false;
        return startedAt.plusMinutes(5).isBefore(LocalDateTime.now());
    }
}