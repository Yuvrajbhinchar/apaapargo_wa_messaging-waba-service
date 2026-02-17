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

/**
 * Service for Project-WABA account assignments
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectWabaService {

    private final ProjectWabaAccountRepository projectWabaRepository;
    private final WabaAccountRepository wabaRepository;

    /**
     * Assign WABA to project
     */
    @Transactional
    public void assignWabaToProject(Long projectId, Long wabaAccountId, boolean setAsDefault) {
        log.info("Assigning wabaAccountId: {} to projectId: {}, default: {}",
                wabaAccountId, projectId, setAsDefault);

        if (!wabaRepository.existsById(wabaAccountId)) {
            throw WabaNotFoundException.withId(wabaAccountId);
        }

        if (projectWabaRepository.existsByProjectIdAndWabaAccountId(projectId, wabaAccountId)) {
            throw new DuplicateWabaException("WABA already assigned to this project");
        }

        // Unset current default if needed
        if (setAsDefault) {
            projectWabaRepository.unsetAllDefaultsForProject(projectId);
        }

        ProjectWabaAccount mapping = ProjectWabaAccount.builder()
                .projectId(projectId)
                .wabaAccountId(wabaAccountId)
                .isDefault(setAsDefault)
                .build();

        projectWabaRepository.save(mapping);
        log.info("WABA assigned to project successfully");
    }

    /**
     * Get all WABAs for a project
     */
    @Transactional(readOnly = true)
    public List<WabaResponse> getWabasForProject(Long projectId) {
        return projectWabaRepository.findByProjectId(projectId)
                .stream()
                .map(mapping -> wabaRepository.findByIdWithPhoneNumbers(mapping.getWabaAccountId())
                        .map(WabaMapper::toWabaResponseWithPhones)
                        .orElse(null))
                .filter(response -> response != null)
                .toList();
    }

    /**
     * Set default WABA for project
     */
    @Transactional
    public void setDefaultWaba(Long projectId, Long wabaAccountId) {
        log.info("Setting default WABA: wabaAccountId={} for projectId={}", wabaAccountId, projectId);

        projectWabaRepository.findByProjectIdAndWabaAccountId(projectId, wabaAccountId)
                .orElseThrow(() -> new WabaNotFoundException("WABA not assigned to this project"));

        projectWabaRepository.unsetAllDefaultsForProject(projectId);

        ProjectWabaAccount mapping = projectWabaRepository
                .findByProjectIdAndWabaAccountId(projectId, wabaAccountId)
                .get();

        mapping.markAsDefault();
        projectWabaRepository.save(mapping);
        log.info("Default WABA updated for project: {}", projectId);
    }

    /**
     * Remove WABA from project
     */
    @Transactional
    public void removeWabaFromProject(Long projectId, Long wabaAccountId) {
        log.info("Removing wabaAccountId: {} from projectId: {}", wabaAccountId, projectId);

        ProjectWabaAccount mapping = projectWabaRepository
                .findByProjectIdAndWabaAccountId(projectId, wabaAccountId)
                .orElseThrow(() -> new WabaNotFoundException("WABA not assigned to this project"));

        projectWabaRepository.delete(mapping);
        log.info("WABA removed from project");
    }
}