package com.aigreentick.services.wabaaccounts.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing a Meta (Facebook) OAuth account.
 *
 *
 * OLD model: Organization → MANY MetaOAuthAccounts (one per Business Manager)
 * Unique constraint: (organization_id, business_manager_id)
 *
 * Problem: A user access token is NOT tied to a Business Manager.
 * One token can access MULTIPLE Business Managers.
 * When a customer connects a second WABA from the same Meta login (different BM),
 * the old model created a second OAuth account row unnecessarily.
 * This caused:
 *   - Duplicate token storage
 *   - Token refresh needing to update multiple rows
 *   - User re-auth creating confusion about which token to use
 *
 * NEW model: Organization → ONE MetaOAuthAccount → MANY WABAs
 * Unique constraint: (organization_id) — one token per org
 *
 * businessManagerId is now stored on WabaAccount (the WABA level),
 * not on the OAuth account level. The token belongs to the org, not the BM.
 *
 * Migration: V3__fix_oauth_domain_model.sql
 *   - Drops old unique constraint on (organization_id, business_manager_id)
 *   - Adds new unique constraint on (organization_id)
 *   - Drops business_manager_id column (now stored elsewhere or derived)
 *   - Adds optional meta_user_id for audit/tracking
 */
@Entity
@Table(
        name = "meta_oauth_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_meta_oauth_org",
                columnNames = {"organization_id"}    // ONE token per org
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaOAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The organization this token belongs to.
     * Unique — one Meta OAuth account per organization.
     */
    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    /**
     * The access token for Meta Graph API calls.
     * After embedded signup: this is a long-lived user token (60 days).
     * After system user provisioning (Phase 2): this becomes a permanent system user token.
     */
    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    /**
     * Token expiry timestamp.
     * null = permanent token (system user tokens don't expire).
     * non-null = user token (60-day expiry, needs refresh scheduler).
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Optional: Meta user ID for audit trail.
     * Populated from /me endpoint after token exchange.
     */
    @Column(name = "meta_user_id", length = 100)
    private String metaUserId;

    /**
     * Phase 2: the system user ID created in Business Manager.
     * Null until Phase 2 provisioning is complete.
     */
    @Column(name = "system_user_id", length = 100)
    private String systemUserId;

    /**
     * Whether this is a user token (60-day) or a permanent system user token.
     * Starts as USER_TOKEN after Phase 1. Becomes SYSTEM_USER after Phase 2.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 20)
    private TokenType tokenType = TokenType.USER_TOKEN;

    /** Distinguishes Phase 1 (user) vs Phase 2 (system) tokens */
    public enum TokenType {
        USER_TOKEN,   // 60-day token — set after embedded signup
        SYSTEM_USER   // Permanent token — set after Phase 2 provisioning
    }

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    /** True if token is expired. System user tokens never expire (expiresAt=null). */
    public boolean isTokenExpired() {
        if (expiresAt == null) return false;
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** True if token expires within 7 days. Triggers refresh scheduler. */
    public boolean isTokenExpiringSoon() {
        if (expiresAt == null) return false;
        return LocalDateTime.now().plusDays(7).isAfter(expiresAt);
    }

    /** True if Phase 2 provisioning is complete (permanent token active). */
    public boolean hasPermanentToken() {
        return TokenType.SYSTEM_USER.equals(tokenType) && systemUserId != null;
    }
}