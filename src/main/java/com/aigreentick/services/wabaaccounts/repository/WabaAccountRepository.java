package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.constants.WabaStatus;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for WABA Account operations
 */
@Repository
public interface WabaAccountRepository extends JpaRepository<WabaAccount, Long> {

    /**
     * Find all WABAs for an organization
     */
    List<WabaAccount> findByOrganizationId(Long organizationId);

    /**
     * Find all WABAs for an organization with pagination
     */
    Page<WabaAccount> findByOrganizationId(Long organizationId, Pageable pageable);

    /**
     * Find WABA by organization ID and WABA ID (unique constraint)
     */
    Optional<WabaAccount> findByOrganizationIdAndWabaId(Long organizationId, String wabaId);

    /**
     * Find by Meta WABA ID
     */
    Optional<WabaAccount> findByWabaId(String wabaId);

    /**
     * Find WABAs by status
     */
    List<WabaAccount> findByOrganizationIdAndStatus(Long organizationId, WabaStatus status);

    /**
     * Check if WABA already exists
     */
    boolean existsByOrganizationIdAndWabaId(Long organizationId, String wabaId);

    /**
     * Count WABAs per organization
     */
    long countByOrganizationId(Long organizationId);

    /**
     * Find WABAs with their phone numbers (avoid N+1)
     */
    @Query("SELECT DISTINCT w FROM WabaAccount w " +
            "LEFT JOIN FETCH w.phoneNumbers " +
            "WHERE w.organizationId = :organizationId")
    List<WabaAccount> findByOrganizationIdWithPhoneNumbers(@Param("organizationId") Long organizationId);

    /**
     * Find WABA with phone numbers by ID
     */
    @Query("SELECT w FROM WabaAccount w " +
            "LEFT JOIN FETCH w.phoneNumbers " +
            "WHERE w.id = :id")
    Optional<WabaAccount> findByIdWithPhoneNumbers(@Param("id") Long id);

    /**
     * Find all suspended WABAs (for admin monitoring)
     */
    List<WabaAccount> findByStatus(WabaStatus status);
}