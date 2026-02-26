package com.aigreentick.services.wabaaccounts.dto.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic wrapper around Meta Graph API responses.
 *
 * Meta returns responses in FOUR different shapes:
 *
 *   1. FLAT (token exchange, token extension):
 *      { "access_token": "EAA...", "token_type": "bearer", "expires_in": 5183944 }
 *      → Fields land in `extras` via @JsonAnySetter
 *      → Read with: getExtras().get("access_token")
 *
 *   2. OBJECT in data wrapper (WABA details, system user creation):
 *      { "data": { "id": "123", "name": "My WABA" } }
 *      → Read with: getDataAsMap().get("id")
 *
 *   3. ARRAY in data wrapper (phone numbers, permissions, businesses):
 *      { "data": [ { "id": "...", "display_phone_number": "..." } ] }
 *      → Read with: getDataAsList()
 *
 *   4. MUTATION SUCCESS:
 *      { "success": true }
 *      → Read with: isOk()
 *
 *   5. ERROR:
 *      { "error": { "message": "...", "type": "OAuthException", "code": 190 } }
 *      → Read with: getErrorMessage(), isOk() returns false
 *
 * CRITICAL: `data` is typed as Object (not Map) to handle both array and object shapes.
 * Previously typed as Map<String, Object> which caused deserialization failure for
 * list endpoints, and getData() always returned null for flat responses.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetaApiResponse {

    /** Present on mutation endpoints (system_users, assigned_users, register, verify_code) */
    @JsonProperty("success")
    private Boolean success;

    /**
     * Data field — can be a Map (object) or List (array) depending on the endpoint.
     * Use getDataAsMap() or getDataAsList() instead of accessing this directly.
     *
     * CHANGED from Map<String, Object> to Object to fix deserialization failures
     * when Meta returns array responses like phone_numbers, permissions, businesses.
     */
    @JsonProperty("data")
    private Object data;

    /**
     * Error info present when the call fails.
     * { "message": "...", "type": "OAuthException", "code": 190 }
     */
    @JsonProperty("error")
    private Map<String, Object> error;

    /**
     * Catch-all for any top-level fields Meta returns that we don't explicitly map.
     *
     * FLAT responses (token exchange) land HERE entirely:
     *   "access_token" → extras.get("access_token")
     *   "token_type"   → extras.get("token_type")
     *   "expires_in"   → extras.get("expires_in")
     *
     * Direct ID responses (system user creation) also land here:
     *   "id" → extras.get("id")
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

    // ════════════════════════════════════════════════════════════
    // TYPED DATA ACCESSORS
    // ════════════════════════════════════════════════════════════

    /**
     * Use for endpoints that return a single object in data:
     *   GET /{wabaId}?fields=...          → { "data": { "id": "...", "name": "..." } }
     *   POST /{businessId}/system_users   → { "data": { "id": "..." } }
     *   POST /{systemUserId}/access_tokens → { "data": { "access_token": "..." } }
     *
     * Returns null if data is a List or not present.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return null;
    }

    /**
     * Use for endpoints that return an array in data:
     *   GET /{wabaId}/phone_numbers       → { "data": [ { "id": "..." } ] }
     *   GET /me/permissions               → { "data": [ { "permission": "...", "status": "..." } ] }
     *   GET /me/businesses                → { "data": [ { "id": "...", "name": "..." } ] }
     *   GET /{wabaId}/subscribed_apps     → { "data": [ { "id": "..." } ] }
     *
     * Returns null if data is a Map or not present.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDataAsList() {
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════
    // CONVENIENCE ACCESSORS
    // ════════════════════════════════════════════════════════════

    /**
     * Get a value from a FLAT response (one that has no data wrapper).
     * Checks extras first, then falls back to dataAsMap.
     *
     * Use for token exchange, token extension responses where fields
     * like access_token/expires_in are at top level.
     */
    public Object getFlatValue(String key) {
        // Flat responses: extras has the data
        if (extras.containsKey(key)) {
            return extras.get(key);
        }
        // Fallback: some endpoints nest in data object
        Map<String, Object> map = getDataAsMap();
        if (map != null) {
            return map.get(key);
        }
        return null;
    }

    /**
     * Convenience: get a top-level "id" field.
     * Some Meta endpoints return { "id": "..." } at top level (extras).
     * Others nest it in data map.
     */
    public String getId() {
        Object id = extras.get("id");
        if (id != null) return String.valueOf(id);
        Map<String, Object> map = getDataAsMap();
        if (map != null) {
            Object dataId = map.get("id");
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
     * True if the call succeeded.
     * Meta uses "success": true on mutation endpoints.
     * For read endpoints, success is implied by absence of "error".
     */
    public boolean isOk() {
        if (error != null) return false;
        if (success != null) return success;
        return true; // Read endpoints don't include "success" key
    }

    public void setError(Map<String, Object> error) {
        this.error = error;
    }

    /**
     * @deprecated Use getDataAsMap() or getDataAsList() or getFlatValue() instead.
     * Kept temporarily to avoid compilation errors during migration — remove after all callers updated.
     */
    @Deprecated
    public Map<String, Object> getData() {
        return getDataAsMap();
    }
}