package com.aigreentick.services.wabaaccounts.repository;

import com.aigreentick.services.wabaaccounts.entity.ProjectWabaAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Project-WABA Account mapping operations
 */
@Repository
public interface ProjectWabaAccountRepository extends JpaRepository<ProjectWabaAccount, Long> {

    /**
     * Find all WABAs for a project
     */
    List<ProjectWabaAccount> findByProjectId(Long projectId);

    /**
     * Find default WABA for a project
     */
    Optional<ProjectWabaAccount> findByProjectIdAndIsDefaultTrue(Long projectId);

    /**
     * Find specific project-WABA mapping
     */
    Optional<ProjectWabaAccount> findByProjectIdAndWabaAccountId(Long projectId, Long wabaAccountId);

    /**
     * Check if mapping already exists
     */
    boolean existsByProjectIdAndWabaAccountId(Long projectId, Long wabaAccountId);

    /**
     * Find all projects using a specific WABA
     */
    List<ProjectWabaAccount> findByWabaAccountId(Long wabaAccountId);

    /**
     * Unset all defaults for a project (before setting new default)
     */
    @Modifying
    @Query("UPDATE ProjectWabaAccount p SET p.isDefault = false WHERE p.projectId = :projectId")
    int unsetAllDefaultsForProject(@Param("projectId") Long projectId);

    /**
     * Count WABAs assigned to a project
     */
    long countByProjectId(Long projectId);
}