package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.dto.response.WabaResponse;
import com.aigreentick.services.wabaaccounts.entity.ProjectWabaAccount;
import com.aigreentick.services.wabaaccounts.exception.DuplicateWabaException;
import com.aigreentick.services.wabaaccounts.exception.WabaNotFoundException;
import com.aigreentick.services.wabaaccounts.mapper.WabaMapper;
import com.aigreentick.services.wabaaccounts.repository.ProjectWabaAccountRepository;
import com.aigreentick.services.wabaaccounts.repository.WabaAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Service for managing Project–WABA account assignments
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectWabaService {

    private final ProjectWabaAccountRepository projectWabaRepository;
    private final WabaAccountRepository wabaRepository;

    // ========================
    // READ
    // ========================

    /**
     * Get all WABAs assigned to a project, with their phone numbers
     */
    @Transactional(readOnly = true)
    public List<WabaResponse> getWabasForProject(Long projectId) {
        log.debug("Fetching WABAs for projectId: {}", projectId);

        return projectWabaRepository.findByProjectId(projectId)
                .stream()
                .map(mapping -> wabaRepository.findByIdWithPhoneNumbers(mapping.getWabaAccountId())
                        .map(WabaMapper::toWabaResponseWithPhones)
                        .orElse(null))
                .filter(Objects::nonNull)   // clean null check — replaces response -> response != null
                .toList();
    }

    // ========================
    // CREATE
    // ========================

    /**
     * Assign a WABA account to a project
     *
     * @param projectId     target project
     * @param wabaAccountId WABA to assign
     * @param setAsDefault  if true, marks this as the default WABA for the project
     */
    @Transactional
    public void assignWabaToProject(Long projectId, Long wabaAccountId, boolean setAsDefault) {
        log.info("Assigning wabaAccountId={} to projectId={}, setAsDefault={}",
                wabaAccountId, projectId, setAsDefault);

        // Guard: WABA must exist
        if (!wabaRepository.existsById(wabaAccountId)) {
            throw WabaNotFoundException.withId(wabaAccountId);
        }

        // Guard: mapping must not already exist
        if (projectWabaRepository.existsByProjectIdAndWabaAccountId(projectId, wabaAccountId)) {
            throw new DuplicateWabaException("WABA already assigned to this project");
        }

        // If setting as default, unset existing default first
        if (setAsDefault) {
            projectWabaRepository.unsetAllDefaultsForProject(projectId);
        }

        ProjectWabaAccount mapping = ProjectWabaAccount.builder()
                .projectId(projectId)
                .wabaAccountId(wabaAccountId)
                .isDefault(setAsDefault)
                .build();

        projectWabaRepository.save(mapping);
        log.info("WABA {} assigned to project {} successfully", wabaAccountId, projectId);
    }

    // ========================
    // UPDATE
    // ========================

    /**
     * Set a specific WABA as the default for a project
     * Unsets all other defaults for the same project first
     *
     * FIX: Single DB query — no duplicate findByProjectIdAndWabaAccountId calls
     */
    @Transactional
    public void setDefaultWaba(Long projectId, Long wabaAccountId) {
        log.info("Setting default WABA: wabaAccountId={} for projectId={}", wabaAccountId, projectId);

        // Single query — fetch the mapping (throws if not found)
        ProjectWabaAccount mapping = projectWabaRepository
                .findByProjectIdAndWabaAccountId(projectId, wabaAccountId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "WABA " + wabaAccountId + " is not assigned to project " + projectId));

        // Unset all current defaults for this project
        projectWabaRepository.unsetAllDefaultsForProject(projectId);

        // Mark the target as default and save
        mapping.markAsDefault();
        projectWabaRepository.save(mapping);

        log.info("Default WABA updated to {} for project {}", wabaAccountId, projectId);
    }

    // ========================
    // DELETE
    // ========================

    /**
     * Remove a WABA assignment from a project
     */
    @Transactional
    public void removeWabaFromProject(Long projectId, Long wabaAccountId) {
        log.info("Removing wabaAccountId={} from projectId={}", wabaAccountId, projectId);

        ProjectWabaAccount mapping = projectWabaRepository
                .findByProjectIdAndWabaAccountId(projectId, wabaAccountId)
                .orElseThrow(() -> new WabaNotFoundException(
                        "WABA " + wabaAccountId + " is not assigned to project " + projectId));

        projectWabaRepository.delete(mapping);
        log.info("WABA {} removed from project {}", wabaAccountId, projectId);
    }
}