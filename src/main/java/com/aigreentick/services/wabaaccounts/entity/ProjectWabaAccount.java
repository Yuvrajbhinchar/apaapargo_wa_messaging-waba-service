package com.aigreentick.services.wabaaccounts.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing the mapping between Projects and WABA accounts
 * Allows projects to use multiple WABA accounts
 */
@Entity
@Table(name = "project_waba_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uniq_project_waba",
                columnNames = {"project_id", "waba_account_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectWabaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "waba_account_id", nullable = false)
    private Long wabaAccountId;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // Relationship
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waba_account_id", insertable = false, updatable = false)
    private WabaAccount wabaAccount;

    /**
     * Mark as default WABA for project
     */
    public void markAsDefault() {
        this.isDefault = true;
    }

    /**
     * Unmark as default
     */
    public void unmarkAsDefault() {
        this.isDefault = false;
    }
}