package com.paycore.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One place for PayCore's own metrics. Everything here shows up in Prometheus under paycore_* names.
 *
 * Counters that describe something done inside a database transaction (a payment changing state) are only
 * incremented AFTER the transaction commits. If the transaction rolls back (idempotent replay, insufficient
 * funds, a fraud decline), nothing was really done, so nothing is counted.
 */
@Component
public class BusinessMetrics {
    private static final List<String> TRANSITIONS =
            List.of("created", "authorized", "captured", "voided", "refunded", "review_approved");

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        // register every series up front so dashboards show 0 instead of "no data" before the first event
        for (String to : TRANSITIONS) {
            registry.counter("paycore.payments.transitions", "to", to);
        }
        registry.counter("paycore.fraud.decisions", "outcome", "declined");
        registry.counter("paycore.fraud.decisions", "outcome", "review");
        registry.counter("paycore.auth.failures", "caller", "merchant");
        registry.counter("paycore.auth.failures", "caller", "admin");
        registry.counter("paycore.ratelimit.rejected", "caller", "merchant");
        registry.counter("paycore.ratelimit.rejected", "caller", "admin");
        registry.counter("paycore.webhooks.dead");
        registry.timer("paycore.webhooks.delivery", "outcome", "delivered");
        registry.timer("paycore.webhooks.delivery", "outcome", "http_error");
        registry.timer("paycore.webhooks.delivery", "outcome", "exception");
    }

    /** A payment moved to a new state (counted after commit). */
    public void transition(String to) {
        afterCommit(() -> registry.counter("paycore.payments.transitions", "to", to).increment());
    }

    /** Fraud rules refused a payment. The transaction rolls back, so this counts immediately. */
    public void fraudDeclined() {
        registry.counter("paycore.fraud.decisions", "outcome", "declined").increment();
    }

    /** Fraud rules created a payment but held it for manual review (counted after commit). */
    public void fraudReviewHeld() {
        afterCommit(() -> registry.counter("paycore.fraud.decisions", "outcome", "review").increment());
    }

    /** caller = "merchant" or "admin". */
    public void authFailure(String caller) {
        registry.counter("paycore.auth.failures", "caller", caller).increment();
    }

    /** caller = "merchant" or "admin". */
    public void rateLimited(String caller) {
        registry.counter("paycore.ratelimit.rejected", "caller", caller).increment();
    }

    /** One webhook delivery attempt. outcome = delivered | http_error | exception. */
    public void webhookAttempt(String outcome, long nanos) {
        registry.timer("paycore.webhooks.delivery", "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** A webhook event ran out of retries and went to the dead-letter queue. */
    public void webhookDead() {
        registry.counter("paycore.webhooks.dead").increment();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
