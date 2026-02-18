package com.aigreentick.services.wabaaccounts.entity;

import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing WhatsApp Business Account (WABA)
 * A WABA can contain multiple phone numbers and belongs to one organization
 */
@Entity
@Table(name = "waba_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uniq_org_waba",
                columnNames = {"organization_id", "waba_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WabaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "meta_oauth_account_id", nullable = false)
    private Long metaOAuthAccountId;

    @Column(name = "waba_id", nullable = false, length = 100)
    private String wabaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WabaStatus status;

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meta_oauth_account_id", insertable = false, updatable = false)
    private MetaOAuthAccount metaOAuthAccount;

    @OneToMany(mappedBy = "wabaAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WabaPhoneNumber> phoneNumbers = new ArrayList<>();

    @OneToMany(mappedBy = "wabaAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProjectWabaAccount> projectWabaAccounts = new ArrayList<>();

    /**
     * Check if WABA is active
     */
    public boolean isActive() {
        return WabaStatus.ACTIVE.equals(this.status);
    }

    /**
     * Check if WABA is suspended
     */
    public boolean isSuspended() {
        return WabaStatus.SUSPENDED.equals(this.status);
    }

    /**
     * Activate WABA
     */
    public void activate() {
        this.status = WabaStatus.ACTIVE;
    }

    /**
     * Suspend WABA
     */
    public void suspend() {
        this.status = WabaStatus.SUSPENDED;
    }

    /**
     * Disconnect WABA
     */
    public void disconnect() {
        this.status = WabaStatus.DISCONNECTED;
    }
}