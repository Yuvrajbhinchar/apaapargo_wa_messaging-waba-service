package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.request.CreateWabaRequest;
import com.aigreentick.services.wabaaccounts.dto.request.UpdateWabaStatusRequest;
import com.aigreentick.services.wabaaccounts.dto.response.ApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaResponse;
import com.aigreentick.services.wabaaccounts.service.WabaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for WABA Account operations
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/waba-accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "WABA Accounts", description = "Manage WhatsApp Business Accounts")
public class WabaController {

    private final WabaService wabaService;

    @PostMapping
    @Operation(summary = "Create WABA account", description = "Called after Meta embedded signup flow completes")
    public ResponseEntity<ApiResponse<WabaResponse>> createWaba(
            @Valid @RequestBody CreateWabaRequest request
    ) {
        log.info("POST /waba-accounts - Creating WABA for orgId: {}", request.getOrganizationId());
        WabaResponse response = wabaService.createWabaAccount(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, WabaConstants.SUCCESS_WABA_CREATED));
    }

    @GetMapping("/{wabaId}")
    @Operation(summary = "Get WABA by ID")
    public ResponseEntity<ApiResponse<WabaResponse>> getWabaById(
            @Parameter(description = "WABA database ID") @PathVariable Long wabaId
    ) {
        log.debug("GET /waba-accounts/{}", wabaId);
        WabaResponse response = wabaService.getWabaById(wabaId);
        return ResponseEntity.ok(ApiResponse.success(response, "WABA fetched successfully"));
    }

    @GetMapping("/organization/{organizationId}")
    @Operation(summary = "Get all WABAs for an organization (paginated)")
    public ResponseEntity<ApiResponse<Page<WabaResponse>>> getWabasByOrganization(
            @Parameter(description = "Organization ID") @PathVariable Long organizationId,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable
    ) {
        log.debug("GET /waba-accounts/organization/{}", organizationId);
        Page<WabaResponse> response = wabaService.getWabasByOrganization(organizationId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response, "WABAs fetched successfully"));
    }

    @GetMapping("/organization/{organizationId}/with-phones")
    @Operation(summary = "Get all WABAs with phone numbers for an organization")
    public ResponseEntity<ApiResponse<List<WabaResponse>>> getWabasWithPhones(
            @PathVariable Long organizationId
    ) {
        log.debug("GET /waba-accounts/organization/{}/with-phones", organizationId);
        List<WabaResponse> response = wabaService.getWabasWithPhonesByOrganization(organizationId);
        return ResponseEntity.ok(ApiResponse.success(response, "WABAs fetched successfully"));
    }

    @PatchMapping("/{wabaId}/status")
    @Operation(summary = "Update WABA status")
    public ResponseEntity<ApiResponse<WabaResponse>> updateStatus(
            @PathVariable Long wabaId,
            @Valid @RequestBody UpdateWabaStatusRequest request
    ) {
        log.info("PATCH /waba-accounts/{}/status - New status: {}", wabaId, request.getStatus());
        WabaResponse response = wabaService.updateWabaStatus(wabaId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "WABA status updated"));
    }

    @PostMapping("/{wabaId}/sync")
    @Operation(summary = "Sync phone numbers from Meta")
    public ResponseEntity<ApiResponse<WabaResponse>> syncPhoneNumbers(
            @PathVariable Long wabaId
    ) {
        log.info("POST /waba-accounts/{}/sync", wabaId);
        WabaResponse response = wabaService.syncPhoneNumbers(wabaId);
        return ResponseEntity.ok(ApiResponse.success(response, "Phone numbers synced"));
    }

    @DeleteMapping("/{wabaId}")
    @Operation(summary = "Disconnect WABA from platform")
    public ResponseEntity<ApiResponse<Void>> disconnectWaba(
            @PathVariable Long wabaId
    ) {
        log.info("DELETE /waba-accounts/{}", wabaId);
        wabaService.disconnectWaba(wabaId);
        return ResponseEntity.ok(ApiResponse.success("WABA disconnected successfully"));
    }
}