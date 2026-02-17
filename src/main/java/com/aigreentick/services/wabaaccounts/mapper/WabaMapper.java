package com.aigreentick.services.wabaaccounts.mapper;

import com.aigreentick.services.wabaaccounts.dto.response.PhoneNumberResponse;
import com.aigreentick.services.wabaaccounts.dto.response.WabaResponse;
import com.aigreentick.services.wabaaccounts.entity.WabaAccount;
import com.aigreentick.services.wabaaccounts.entity.WabaPhoneNumber;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper utility for converting between WABA entities and DTOs
 */
@UtilityClass
public class WabaMapper {

    /**
     * Map WabaAccount entity to WabaResponse DTO
     */
    public WabaResponse toWabaResponse(WabaAccount entity) {
        if (entity == null) return null;

        return WabaResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .wabaId(entity.getWabaId())
                .status(entity.getStatus() != null ? entity.getStatus().getValue() : null)
                .phoneNumberCount(entity.getPhoneNumbers() != null
                        ? entity.getPhoneNumbers().size() : 0)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Map WabaAccount with phone numbers to WabaResponse DTO
     */
    public WabaResponse toWabaResponseWithPhones(WabaAccount entity) {
        if (entity == null) return null;

        WabaResponse response = toWabaResponse(entity);

        if (entity.getPhoneNumbers() != null && !entity.getPhoneNumbers().isEmpty()) {
            List<PhoneNumberResponse> phoneResponses = entity.getPhoneNumbers()
                    .stream()
                    .map(WabaMapper::toPhoneNumberResponse)
                    .collect(Collectors.toList());
            response.setPhoneNumbers(phoneResponses);
        } else {
            response.setPhoneNumbers(Collections.emptyList());
        }

        return response;
    }

    /**
     * Map WabaPhoneNumber entity to PhoneNumberResponse DTO
     */
    public PhoneNumberResponse toPhoneNumberResponse(WabaPhoneNumber entity) {
        if (entity == null) return null;

        return PhoneNumberResponse.builder()
                .id(entity.getId())
                .wabaAccountId(entity.getWabaAccountId())
                .phoneNumberId(entity.getPhoneNumberId())
                .displayPhoneNumber(entity.getDisplayPhoneNumber())
                .status(entity.getStatus() != null ? entity.getStatus().getValue() : null)
                .verifiedName(entity.getVerifiedName())
                .qualityRating(entity.getQualityRating() != null
                        ? entity.getQualityRating().getValue() : null)
                .messagingLimitTier(entity.getMessagingLimitTier() != null
                        ? entity.getMessagingLimitTier().getValue() : null)
                .dailyLimit(entity.getDailyMessagingLimit())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * Map list of WabaAccount to list of WabaResponse
     */
    public List<WabaResponse> toWabaResponseList(List<WabaAccount> entities) {
        if (entities == null) return Collections.emptyList();
        return entities.stream()
                .map(WabaMapper::toWabaResponse)
                .collect(Collectors.toList());
    }
}