package com.paycore.web;

import com.paycore.security.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Admin-only (X-Admin-Key): issue and revoke merchant API keys. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    public record CreateKeyRequest(@NotBlank String name) {}

    private final ApiKeyService apiKeys;

    public AdminController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping("/merchants/{merchantId}/api-keys")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable UUID merchantId,
                                                     @Valid @RequestBody CreateKeyRequest req) {
        ApiKeyService.IssuedKey key = apiKeys.issue(merchantId, req.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", key.id());
        body.put("apiKey", key.apiKey());
        body.put("prefix", key.prefix());
        body.put("warning", "Store this key now. It is not stored in plaintext and cannot be shown again.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID keyId) {
        apiKeys.revoke(keyId);
        return ResponseEntity.noContent().build();
    }
}
