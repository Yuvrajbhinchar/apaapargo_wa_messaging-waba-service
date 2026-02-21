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

@Repository
public interface WabaAccountRepository extends JpaRepository<WabaAccount, Long> {

    List<WabaAccount> findByOrganizationId(Long organizationId);

    Page<WabaAccount> findByOrganizationId(Long organizationId, Pageable pageable);

    Optional<WabaAccount> findByOrganizationIdAndWabaId(Long organizationId, String wabaId);

    Optional<WabaAccount> findByWabaId(String wabaId);

    List<WabaAccount> findByOrganizationIdAndStatus(Long organizationId, WabaStatus status);

    /**
     * FIX: Global uniqueness check — is this WABA connected to ANY org?
     * Use this BEFORE existsByOrganizationIdAndWabaId to detect cross-tenant conflicts.
     */
    boolean existsByWabaId(String wabaId);

    /**
     * Per-org uniqueness check. Use AFTER existsByWabaId for specific messaging.
     */
    boolean existsByOrganizationIdAndWabaId(Long organizationId, String wabaId);

    long countByOrganizationId(Long organizationId);

    @Query("SELECT DISTINCT w FROM WabaAccount w " +
            "LEFT JOIN FETCH w.phoneNumbers " +
            "WHERE w.organizationId = :organizationId")
    List<WabaAccount> findByOrganizationIdWithPhoneNumbers(@Param("organizationId") Long organizationId);

    @Query("SELECT w FROM WabaAccount w " +
            "LEFT JOIN FETCH w.phoneNumbers " +
            "WHERE w.id = :id")
    Optional<WabaAccount> findByIdWithPhoneNumbers(@Param("id") Long id);

    List<WabaAccount> findByStatus(WabaStatus status);
}