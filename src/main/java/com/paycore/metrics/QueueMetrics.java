package com.paycore.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue sizes exposed as gauges. The values are refreshed by a background job every 15 seconds and the gauges just
 * read the cached numbers, so a Prometheus scrape never touches the database.
 */
@Component
public class QueueMetrics {
    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong pendingWebhooks = new AtomicLong();
    private final AtomicLong deadWebhooks = new AtomicLong();
    private final AtomicLong paymentsInReview = new AtomicLong();

    public QueueMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("paycore.webhooks.events", pendingWebhooks, AtomicLong::get)
                .tag("status", "pending")
                .description("Webhook events waiting for delivery or a retry")
                .register(registry);
        Gauge.builder("paycore.webhooks.events", deadWebhooks, AtomicLong::get)
                .tag("status", "dead")
                .description("Webhook events in the dead-letter queue")
                .register(registry);
        Gauge.builder("paycore.payments.review.queue", paymentsInReview, AtomicLong::get)
                .description("Payments held for manual fraud review")
                .register(registry);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 3_000)
    public void refresh() {
        try {
            pendingWebhooks.set(count("SELECT COUNT(*) FROM webhook_events WHERE status = 'PENDING'"));
            deadWebhooks.set(count("SELECT COUNT(*) FROM webhook_events WHERE status = 'DEAD'"));
            paymentsInReview.set(count("SELECT COUNT(*) FROM payments WHERE risk_flag = 'REVIEW'"));
        } catch (Exception e) {
            log.warn("Could not refresh queue metrics: {}", e.toString());
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
