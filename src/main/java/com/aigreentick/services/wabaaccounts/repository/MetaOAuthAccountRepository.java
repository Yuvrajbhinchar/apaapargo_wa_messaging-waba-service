package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface MetaOAuthAccountRepository extends JpaRepository<MetaOAuthAccount, Long> {

    /**
     * Primary lookup — get the OAuth account for an organization.
     * Returns Optional because an org may not have connected yet.
     * There is at most ONE result due to the UNIQUE constraint on organization_id.
     */
    Optional<MetaOAuthAccount> findByOrganizationId(Long organizationId);

    /**
     * Check if an org already has a connected OAuth account.
     * Used in WabaService before creating a new entry.
     */
    boolean existsByOrganizationId(Long organizationId);

    /**
     * Find all tokens expiring before a given threshold.
     * Used by a scheduled job to proactively refresh tokens before they expire.
     * Tokens with expiresAt = null are permanent (system user) and are excluded.
     */
    @Query("SELECT m FROM MetaOAuthAccount m " +
            "WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :threshold")
    List<MetaOAuthAccount> findExpiredOrExpiringSoon(@Param("threshold") LocalDateTime threshold);

    /**
     * Find all user tokens (non-permanent).
     * System user tokens have expiresAt = null; user tokens have a 60-day expiry.
     * Used for Phase 2: identifying which orgs still need system user provisioning.
     */
    @Query("SELECT m FROM MetaOAuthAccount m WHERE m.expiresAt IS NOT NULL")
    List<MetaOAuthAccount> findAllUserTokenAccounts();
}