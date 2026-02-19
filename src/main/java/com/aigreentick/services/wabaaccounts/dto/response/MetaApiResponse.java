package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic wrapper around Meta Graph API responses.
 *
 * Meta returns responses in different shapes depending on the endpoint.
 * Rather than creating a DTO per endpoint, we use a flexible wrapper:
 *
 *   Success with data:
 *     { "data": { "access_token": "...", "token_type": "bearer" }, "success": true }
 *
 *   Simple success:
 *     { "success": true }
 *
 *   Error:
 *     { "error": { "message": "...", "type": "OAuthException", "code": 190 } }
 *
 *   List response:
 *     { "data": [ { "id": "...", "name": "..." }, ... ] }
 *
 * The {@code data} field is captured as Map<String, Object> for flexibility.
 * Callers validate getSuccess() before using getData().
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetaApiResponse {

    /** Present on mutation endpoints (system_users, assigned_users) */
    @JsonProperty("success")
    private Boolean success;

    /**
     * Flat key-value data for single-object responses.
     * e.g. { "access_token": "...", "id": "..." }
     */
    @JsonProperty("data")
    private Map<String, Object> data;

    /**
     * Error info present when the call fails.
     * { "message": "...", "type": "OAuthException", "code": 190 }
     */
    @JsonProperty("error")
    private Map<String, Object> error;

    /**
     * Catch-all for any top-level fields we don't explicitly map.
     * e.g. "id" returned directly for some endpoints.
     */
    private Map<String, Object> extras = new LinkedHashMap<>();

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extras.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras;
    }

    /**
     * Convenience: get a top-level "id" field that Meta sometimes
     * returns directly (e.g. system user creation).
     */
    public String getId() {
        // Some Meta endpoints return { "id": "..." } at top level
        Object id = extras.get("id");
        if (id != null) return String.valueOf(id);
        // Others nest it in data
        if (data != null) {
            Object dataId = data.get("id");
            if (dataId != null) return String.valueOf(dataId);
        }
        return null;
    }

    /**
     * Get error message if present, or null.
     */
    public String getErrorMessage() {
        if (error == null) return null;
        Object msg = error.get("message");
        return msg != null ? String.valueOf(msg) : "Unknown Meta API error";
    }

    /**
     * True if the call succeeded. Meta uses "success": true on mutation
     * endpoints; for read endpoints, success is implied by absence of "error".
     */
    public boolean isOk() {
        if (error != null) return false;
        if (success != null) return success;
        return true; // Read endpoints don't include "success" key
    }
}