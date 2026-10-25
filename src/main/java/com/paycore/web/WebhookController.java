package com.paycore.web;

import com.paycore.common.ApiException;
import com.paycore.security.ApiAuthFilter;
import com.paycore.security.AuthContext;
import com.paycore.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Merchant API key required (Authorization: Bearer pk_...). A merchant only ever sees its own data. */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    public record CreateEndpointRequest(@NotBlank @Size(max = 500) String url) {}

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest http,
                                                      @Valid @RequestBody CreateEndpointRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(webhooks.createEndpoint(merchantId(http), req.url()));
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest http) {
        return webhooks.listEndpoints(merchantId(http));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(HttpServletRequest http, @PathVariable UUID id) {
        webhooks.deactivateEndpoint(merchantId(http), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(HttpServletRequest http,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "50") int limit) {
        return webhooks.listEvents(merchantId(http), status, limit);
    }

    @PostMapping("/events/{id}/replay")
    public ResponseEntity<Map<String, String>> replay(HttpServletRequest http, @PathVariable UUID id) {
        webhooks.replay(merchantId(http), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "PENDING"));
    }

    private static UUID merchantId(HttpServletRequest http) {
        Object a = http.getAttribute(ApiAuthFilter.ATTR);
        if (a instanceof AuthContext ctx && ctx.merchantId() != null) return ctx.merchantId();
        throw new ApiException(HttpStatus.UNAUTHORIZED, "A merchant API key is required");
    }
}
