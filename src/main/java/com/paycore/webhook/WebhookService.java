package com.paycore.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paycore.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Endpoint management, the outbox write (enqueue), event listing and replay. */
@Service
public class WebhookService {
    private static final int MAX_ENDPOINTS_PER_MERCHANT = 5;
    private static final Set<String> STATUSES = Set.of("PENDING", "DELIVERED", "DEAD");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean allowPrivate;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(JdbcTemplate jdbc, ObjectMapper mapper,
                          @Value("${paycore.webhooks.allow-private-targets:false}") boolean allowPrivate) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.allowPrivate = allowPrivate;
    }

    /**
     * THE OUTBOX WRITE. Called from inside the payment transaction (MANDATORY = refuses to run
     * without one), so the event row commits or rolls back together with the money movement.
     * Nothing is sent here; the dispatcher delivers it afterwards.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID merchantId, String eventType, Object data) {
        List<UUID> endpoints = jdbc.query(
                "SELECT id FROM webhook_endpoints WHERE merchant_id = ? AND active = TRUE",
                (rs, i) -> rs.getObject("id", UUID.class), merchantId);
        if (endpoints.isEmpty()) return;

        JsonNode dataNode = mapper.valueToTree(data);
        String createdAt = Instant.now().toString();
        for (UUID endpointId : endpoints) {
            UUID eventId = UUID.randomUUID();
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("id", eventId.toString());
            envelope.put("type", eventType);
            envelope.put("createdAt", createdAt);
            envelope.set("data", dataNode);
            jdbc.update("INSERT INTO webhook_events (id, merchant_id, endpoint_id, event_type, payload) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    eventId, merchantId, endpointId, eventType, envelope.toString());
        }
    }

    @Transactional
    public Map<String, Object> createEndpoint(UUID merchantId, String url) {
        WebhookUrls.assertAllowed(url, allowPrivate);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_endpoints WHERE merchant_id = ? AND active = TRUE",
                Integer.class, merchantId);
        if (active != null && active >= MAX_ENDPOINTS_PER_MERCHANT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_ENDPOINTS_PER_MERCHANT + " active webhook endpoints reached");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_endpoints (id, merchant_id, url, secret) VALUES (?, ?, ?, ?)",
                id, merchantId, url, secret);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("url", url);
        result.put("secret", secret);
        result.put("active", true);
        result.put("note", "Use the secret to verify the X-PayCore-Signature header on incoming webhooks.");
        return result;
    }

    public List<Map<String, Object>> listEndpoints(UUID merchantId) {
        return jdbc.queryForList(
                "SELECT id, url, active, created_at FROM webhook_endpoints WHERE merchant_id = ? "
                        + "ORDER BY created_at DESC", merchantId);
    }

    @Transactional
    public void deactivateEndpoint(UUID merchantId, UUID endpointId) {
        int n = jdbc.update("UPDATE webhook_endpoints SET active = FALSE WHERE id = ? AND merchant_id = ?",
                endpointId, merchantId);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Webhook endpoint not found");
    }

    public List<Map<String, Object>> listEvents(UUID merchantId, String status, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        String cols = "SELECT id, endpoint_id, event_type, status, attempts, next_attempt_at, "
                + "last_status_code, last_error, created_at, delivered_at, payload FROM webhook_events ";
        if (status == null || status.isBlank()) {
            return jdbc.queryForList(cols + "WHERE merchant_id = ? ORDER BY created_at DESC LIMIT ?",
                    merchantId, lim);
        }
        String s = status.toUpperCase();
        if (!STATUSES.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "status must be one of " + STATUSES);
        }
        return jdbc.queryForList(cols + "WHERE merchant_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?",
                merchantId, s, lim);
    }

    /** Puts a DEAD event back in the queue with a fresh retry budget. */
    @Transactional
    public void replay(UUID merchantId, UUID eventId) {
        int n = jdbc.update("UPDATE webhook_events SET status = 'PENDING', attempts = 0, "
                + "next_attempt_at = now(), last_error = NULL "
                + "WHERE id = ? AND merchant_id = ? AND status = 'DEAD'", eventId, merchantId);
        if (n == 1) return;
        List<Map<String, Object>> row = jdbc.queryForList(
                "SELECT status FROM webhook_events WHERE id = ? AND merchant_id = ?", eventId, merchantId);
        if (row.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Event not found");
        throw new ApiException(HttpStatus.CONFLICT,
                "Only DEAD events can be replayed (this one is " + row.get(0).get("status") + ")");
    }
}
