package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.response.ApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaResponse;
import com.aigreentick.services.wabaaccounts.service.ProjectWabaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for Project-WABA assignments
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/projects/{projectId}/waba-accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Project WABA Accounts", description = "Manage WABA assignments for projects")
public class ProjectWabaController {

    private final ProjectWabaService projectWabaService;

    @GetMapping
    @Operation(summary = "Get all WABAs for a project")
    public ResponseEntity<ApiResponse<List<WabaResponse>>> getProjectWabas(
            @PathVariable Long projectId
    ) {
        List<WabaResponse> response = projectWabaService.getWabasForProject(projectId);
        return ResponseEntity.ok(ApiResponse.success(response, "Project WABAs fetched"));
    }

    @PostMapping("/{wabaAccountId}")
    @Operation(summary = "Assign WABA to project")
    public ResponseEntity<ApiResponse<Void>> assignWaba(
            @PathVariable Long projectId,
            @PathVariable Long wabaAccountId,
            @RequestParam(defaultValue = "false") boolean setAsDefault
    ) {
        log.info("Assigning waba {} to project {}", wabaAccountId, projectId);
        projectWabaService.assignWabaToProject(projectId, wabaAccountId, setAsDefault);
        return ResponseEntity.ok(ApiResponse.success("WABA assigned to project"));
    }

    @PatchMapping("/{wabaAccountId}/default")
    @Operation(summary = "Set WABA as default for project")
    public ResponseEntity<ApiResponse<Void>> setDefault(
            @PathVariable Long projectId,
            @PathVariable Long wabaAccountId
    ) {
        projectWabaService.setDefaultWaba(projectId, wabaAccountId);
        return ResponseEntity.ok(ApiResponse.success("Default WABA updated"));
    }

    @DeleteMapping("/{wabaAccountId}")
    @Operation(summary = "Remove WABA from project")
    public ResponseEntity<ApiResponse<Void>> removeWaba(
            @PathVariable Long projectId,
            @PathVariable Long wabaAccountId
    ) {
        projectWabaService.removeWabaFromProject(projectId, wabaAccountId);
        return ResponseEntity.ok(ApiResponse.success("WABA removed from project"));
    }
}