package com.aigreentick.services.wabaaccounts.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing Meta (Facebook) Business Manager OAuth account
 * Stores access tokens for WhatsApp Business API access
 */
@Entity
@Table(name = "meta_oauth_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaOAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "business_manager_id", nullable = false, length = 100)
    private String businessManagerId;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false, insertable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * Check if access token is expired
     */
    public boolean isTokenExpired() {
        if (expiresAt == null) {
            return false; // Long-lived tokens don't expire
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if token will expire soon (within 7 days)
     */
    public boolean isTokenExpiringSoon() {
        if (expiresAt == null) {
            return false;
        }
        return LocalDateTime.now().plusDays(7).isAfter(expiresAt);
    }
}