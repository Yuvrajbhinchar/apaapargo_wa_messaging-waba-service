package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

/**
 * Generic response from Meta Graph API
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetaApiResponse {

    private Boolean success;
    private Map<String, Object> data;
    private String errorMessage;
    private String errorCode;

    public static MetaApiResponse success(Map<String, Object> data) {
        return MetaApiResponse.builder()
                .success(true)
                .data(data)
                .build();
    }

    public static MetaApiResponse error(String message, String code) {
        return MetaApiResponse.builder()
                .success(false)
                .errorMessage(message)
                .errorCode(code)
                .build();
    }
}