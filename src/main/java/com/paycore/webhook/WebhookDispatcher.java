package com.paycore.webhook;

import com.paycore.metrics.BusinessMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The delivery worker. Every second it:
 *   1. CLAIMS due events with  FOR UPDATE SKIP LOCKED  (several app servers can run this at once
 *      and never grab the same row) and pushes next_attempt_at forward by a lease. If this server
 *      dies mid-delivery, the lease expires and another worker retries: at-least-once delivery.
 *   2. SENDS each event over HTTP, outside any DB transaction, 8 at a time.
 *   3. Records the result: DELIVERED, or reschedule with exponential backoff + jitter,
 *      or DEAD (dead-letter) once max attempts is reached.
 * Delivery is at-least-once and unordered: receivers should de-duplicate on the event id.
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final JdbcTemplate jdbc;
    private final BusinessMetrics metrics;
    private final int maxAttempts;
    private final int baseDelaySeconds;
    private final int maxDelaySeconds;
    private final int leaseSeconds;
    private final int batchSize;
    private final boolean allowPrivate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)   // a redirect could bounce us to an internal address
            .build();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public WebhookDispatcher(JdbcTemplate jdbc, BusinessMetrics metrics,
                             @Value("${paycore.webhooks.max-attempts:8}") int maxAttempts,
                             @Value("${paycore.webhooks.base-delay-seconds:2}") int baseDelaySeconds,
                             @Value("${paycore.webhooks.max-delay-seconds:3600}") int maxDelaySeconds,
                             @Value("${paycore.webhooks.lease-seconds:60}") int leaseSeconds,
                             @Value("${paycore.webhooks.batch-size:20}") int batchSize,
                             @Value("${paycore.webhooks.allow-private-targets:false}") boolean allowPrivate) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
        this.allowPrivate = allowPrivate;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    @Scheduled(fixedDelayString = "${paycore.webhooks.poll-interval-ms:1000}")
    public void tick() {
        try {
            List<Map<String, Object>> claimed = claim();
            if (claimed.isEmpty()) return;
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Map<String, Object> row : claimed) {
                tasks.add(() -> {
                    deliver(row);
                    return null;
                });
            }
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Webhook dispatcher tick failed", e);
        }
    }

    /** One atomic statement: pick due rows, skip rows other workers hold, take a lease, count the attempt. */
    private List<Map<String, Object>> claim() {
        String sql = "UPDATE webhook_events "
                + "SET attempts = attempts + 1, next_attempt_at = now() + interval '" + leaseSeconds + " seconds' "
                + "WHERE id IN ("
                + "  SELECT id FROM webhook_events "
                + "  WHERE status = 'PENDING' AND next_attempt_at <= now() "
                + "  ORDER BY next_attempt_at LIMIT ? "
                + "  FOR UPDATE SKIP LOCKED) "
                + "RETURNING id, endpoint_id, event_type, payload, attempts";
        return jdbc.queryForList(sql, batchSize);
    }

    private void deliver(Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        UUID endpointId = (UUID) row.get("endpoint_id");
        int attempts = ((Number) row.get("attempts")).intValue();
        String body = row.get("payload").toString();
        final long started = System.nanoTime();
        try {
            Map<String, Object> endpoint = jdbc.queryForMap(
                    "SELECT url, secret, active FROM webhook_endpoints WHERE id = ?", endpointId);
            if (!Boolean.TRUE.equals(endpoint.get("active"))) {
                markFailure(id, attempts, null, "Endpoint was deactivated", true);
                return;
            }
            String url = endpoint.get("url").toString();
            WebhookUrls.assertAllowed(url, allowPrivate);

            long timestamp = Instant.now().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PayCore-Webhooks/1.0")
                    .header("X-PayCore-Event-Id", id.toString())
                    .header("X-PayCore-Event-Type", row.get("event_type").toString())
                    .header("X-PayCore-Timestamp", String.valueOf(timestamp))
                    .header("X-PayCore-Signature",
                            WebhookSigner.header(endpoint.get("secret").toString(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            long elapsed = System.nanoTime() - started;
            if (code >= 200 && code < 300) {
                metrics.webhookAttempt("delivered", elapsed);
                markDelivered(id, code);
            } else {
                metrics.webhookAttempt("http_error", elapsed);
                markFailure(id, attempts, code, "HTTP " + code, false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            metrics.webhookAttempt("exception", System.nanoTime() - started);
            markFailure(id, attempts, null, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    private void markDelivered(UUID id, int statusCode) {
        jdbc.update("UPDATE webhook_events SET status = 'DELIVERED', delivered_at = now(), "
                + "last_status_code = ?, last_error = NULL WHERE id = ?", statusCode, id);
    }

    private void markFailure(UUID id, int attempts, Integer statusCode, String error, boolean permanent) {
        String err = error == null ? "unknown error" : (error.length() > 500 ? error.substring(0, 500) : error);
        if (permanent || attempts >= maxAttempts) {
            jdbc.update("UPDATE webhook_events SET status = 'DEAD', last_status_code = ?, last_error = ? "
                    + "WHERE id = ?", statusCode, err, id);
            metrics.webhookDead();
            log.warn("Webhook event {} moved to dead-letter after {} attempts: {}", id, attempts, err);
        } else {
            long delayMs = backoffMillis(attempts);
            jdbc.update("UPDATE webhook_events SET next_attempt_at = now() + (?::float8 * interval '1 millisecond'), "
                    + "last_status_code = ?, last_error = ? WHERE id = ?", delayMs, statusCode, err, id);
            log.info("Webhook event {} attempt {} failed ({}); retrying in {} ms", id, attempts, err, delayMs);
        }
    }

    /** base * 2^(attempt-1), capped, with +/-20% jitter so a recovering endpoint isn't hit by a thundering herd. */
    long backoffMillis(int attempts) {
        double exponential = baseDelaySeconds * 1000.0 * Math.pow(2, attempts - 1);
        double capped = Math.min(exponential, maxDelaySeconds * 1000.0);
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return (long) (capped * jitter);
    }
}
