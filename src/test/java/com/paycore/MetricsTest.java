package com.paycore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks PayCore's own metrics through the MeterRegistry. (Spring Boot switches the Prometheus exporter off inside
 * tests, so the HTTP scrape endpoint is verified by hand against the running stack instead.)
 */
class MetricsTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry registry;

    private double count(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void paymentTransitionsAreCountedOncePerRealStateChange() {
        double created = count("paycore.payments.transitions", "to", "created");
        double authorized = count("paycore.payments.transitions", "to", "authorized");
        double captured = count("paycore.payments.transitions", "to", "captured");

        Merchant m = newMerchant();
        String customer = newAccount("metrics-customer", "CUSTOMER");
        deposit(customer, 1000);
        String paymentId = createPayment(m, customer, 100);
        act(m, paymentId, "authorize");
        act(m, paymentId, "capture");
        act(m, paymentId, "capture");   // rejected (409): must NOT be counted

        assertThat((long) (count("paycore.payments.transitions", "to", "created") - created)).isEqualTo(1);
        assertThat((long) (count("paycore.payments.transitions", "to", "authorized") - authorized)).isEqualTo(1);
        assertThat((long) (count("paycore.payments.transitions", "to", "captured") - captured)).isEqualTo(1);
    }

    @Test
    void anIdempotentReplayIsNotCountedAgain() {
        double before = count("paycore.payments.transitions", "to", "created");
        Merchant m = newMerchant();
        String customer = newAccount("replay-customer", "CUSTOMER");
        String key = uuid();
        Map<String, Object> body = Map.of("customerId", customer, "merchantId", m.id(), "amount", 100);

        merchantCall(m, "POST", "/payments", body, key);
        merchantCall(m, "POST", "/payments", body, key);   // replay
        merchantCall(m, "POST", "/payments", body, key);   // replay

        assertThat((long) (count("paycore.payments.transitions", "to", "created") - before)).isEqualTo(1);
    }

    @Test
    void authenticationFailuresAreCounted() {
        double merchantBefore = count("paycore.auth.failures", "caller", "merchant");
        double adminBefore = count("paycore.auth.failures", "caller", "admin");

        assertThat(call("POST", "/payments", Map.of("customerId", uuid(), "merchantId", uuid(), "amount", 1),
                "Idempotency-Key", uuid()).status()).isEqualTo(401);
        assertThat(call("GET", "/admin/reconcile", null).status()).isEqualTo(401);

        assertThat((long) (count("paycore.auth.failures", "caller", "merchant") - merchantBefore)).isEqualTo(1);
        assertThat((long) (count("paycore.auth.failures", "caller", "admin") - adminBefore)).isEqualTo(1);
    }

    @Test
    void queueGaugesAreRegistered() {
        Gauge dead = registry.find("paycore.webhooks.events").tag("status", "dead").gauge();
        Gauge pending = registry.find("paycore.webhooks.events").tag("status", "pending").gauge();
        Gauge review = registry.find("paycore.payments.review.queue").gauge();

        assertThat(dead != null).isTrue();
        assertThat(pending != null).isTrue();
        assertThat(review != null).isTrue();
    }
}
