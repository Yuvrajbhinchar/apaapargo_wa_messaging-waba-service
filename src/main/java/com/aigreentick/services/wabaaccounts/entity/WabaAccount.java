package com.aigreentick.services.wabaaccounts.entity;

import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "waba_accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_waba_accounts_org_waba",
                        columnNames = {"organization_id", "waba_id"}),
                @UniqueConstraint(
                        name = "uq_waba_accounts_waba_id",
                        columnNames = {"waba_id"})
        }
)
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

    @Column(name = "business_manager_id", length = 100)
    private String businessManagerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WabaStatus status;

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meta_oauth_account_id", insertable = false, updatable = false)
    private MetaOAuthAccount metaOAuthAccount;

    @OneToMany(mappedBy = "wabaAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WabaPhoneNumber> phoneNumbers = new ArrayList<>();

    @OneToMany(mappedBy = "wabaAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProjectWabaAccount> projectWabaAccounts = new ArrayList<>();

    public boolean isActive() {
        return WabaStatus.ACTIVE.equals(this.status);
    }

    public boolean isSuspended() {
        return WabaStatus.SUSPENDED.equals(this.status);
    }

    public void activate() { this.status = WabaStatus.ACTIVE; }
    public void suspend()  { this.status = WabaStatus.SUSPENDED; }
    public void disconnect() { this.status = WabaStatus.DISCONNECTED; }
}