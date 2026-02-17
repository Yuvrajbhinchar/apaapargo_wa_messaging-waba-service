package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.entity.MetaOAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Meta OAuth Account operations
 */
@Repository
public interface MetaOAuthAccountRepository extends JpaRepository<MetaOAuthAccount, Long> {

    /**
     * Find by organization ID
     */
    List<MetaOAuthAccount> findByOrganizationId(Long organizationId);

    /**
     * Find by organization ID and business manager ID
     */
    Optional<MetaOAuthAccount> findByOrganizationIdAndBusinessManagerId(
            Long organizationId,
            String businessManagerId
    );

    /**
     * Find all tokens expiring before a given time (for refresh scheduling)
     */
    @Query("SELECT m FROM MetaOAuthAccount m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :threshold")
    List<MetaOAuthAccount> findExpiredOrExpiringSoon(@Param("threshold") LocalDateTime threshold);

    /**
     * Check if organization already has a business manager connected
     */
    boolean existsByOrganizationIdAndBusinessManagerId(Long organizationId, String businessManagerId);
}