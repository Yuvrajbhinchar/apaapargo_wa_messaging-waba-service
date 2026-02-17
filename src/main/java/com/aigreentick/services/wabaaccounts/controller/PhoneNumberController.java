package com.aigreentick.services.wabaaccounts.controller;

import com.aigreentick.services.wabaaccounts.constants.WabaConstants;
import com.aigreentick.services.wabaaccounts.dto.request.RegisterPhoneNumberRequest;
import com.aigreentick.services.wabaaccounts.dto.request.RequestVerificationCodeRequest;
import com.aigreentick.services.wabaaccounts.dto.request.VerifyPhoneNumberRequest;
import com.aigreentick.services.wabaaccounts.dto.response.ApiResponse;
import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.service.PhoneNumberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Phone Number operations
 */
@RestController
@RequestMapping(WabaConstants.API_V1 + "/phone-numbers")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Phone Numbers", description = "Manage WhatsApp Business phone numbers")
public class PhoneNumberController {

    private final PhoneNumberService phoneNumberService;

    @GetMapping("/waba/{wabaAccountId}")
    @Operation(summary = "Get all phone numbers for a WABA")
    public ResponseEntity<ApiResponse<List<PhoneNumberResponse>>> getByWaba(
            @PathVariable Long wabaAccountId
    ) {
        log.debug("GET /phone-numbers/waba/{}", wabaAccountId);
        List<PhoneNumberResponse> response = phoneNumberService.getPhoneNumbersByWaba(wabaAccountId);
        return ResponseEntity.ok(ApiResponse.success(response, "Phone numbers fetched"));
    }

    @GetMapping("/{phoneNumberId}")
    @Operation(summary = "Get phone number by ID")
    public ResponseEntity<ApiResponse<PhoneNumberResponse>> getById(
            @PathVariable Long phoneNumberId
    ) {
        PhoneNumberResponse response = phoneNumberService.getPhoneNumberById(phoneNumberId);
        return ResponseEntity.ok(ApiResponse.success(response, "Phone number fetched"));
    }

    @PostMapping("/register")
    @Operation(summary = "Register phone number with Meta")
    public ResponseEntity<ApiResponse<PhoneNumberResponse>> registerPhone(
            @Valid @RequestBody RegisterPhoneNumberRequest request
    ) {
        log.info("POST /phone-numbers/register - phoneNumberId: {}", request.getPhoneNumberId());
        PhoneNumberResponse response = phoneNumberService.registerPhoneNumber(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Phone number registered successfully"));
    }

    @PostMapping("/verification/request")
    @Operation(summary = "Request verification code via SMS or Voice")
    public ResponseEntity<ApiResponse<Void>> requestVerificationCode(
            @Valid @RequestBody RequestVerificationCodeRequest request
    ) {
        log.info("POST /phone-numbers/verification/request - method: {}", request.getMethod());
        phoneNumberService.requestVerificationCode(request);
        return ResponseEntity.ok(ApiResponse.success("Verification code sent"));
    }

    @PostMapping("/verification/verify")
    @Operation(summary = "Verify phone number with OTP code")
    public ResponseEntity<ApiResponse<PhoneNumberResponse>> verifyPhoneNumber(
            @Valid @RequestBody VerifyPhoneNumberRequest request
    ) {
        log.info("POST /phone-numbers/verification/verify");
        PhoneNumberResponse response = phoneNumberService.verifyPhoneNumber(request);
        return ResponseEntity.ok(ApiResponse.success(response, WabaConstants.SUCCESS_PHONE_VERIFIED));
    }
}