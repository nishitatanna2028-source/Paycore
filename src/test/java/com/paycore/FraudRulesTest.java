package com.paycore;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "paycore.fraud.velocity-max-per-minute=5")
class FraudRulesTest extends AbstractIntegrationTest {

    @Test
    void theSixthPaymentFromOneCustomerWithinAMinuteIsDeclined() {
        Merchant m = newMerchant();
        String customer = newAccount("bot", "CUSTOMER");

        for (int i = 1; i <= 5; i++) {
            assertThat(createPaymentRaw(m, customer, 100).status()).as("payment %d", i).isEqualTo(201);
        }
        Resp sixth = createPaymentRaw(m, customer, 100);

        assertThat(sixth.status()).isEqualTo(422);
        assertThat(sixth.body().get("error").asText()).contains("too many payments");
        // a different customer is not affected
        assertThat(createPaymentRaw(m, newAccount("innocent", "CUSTOMER"), 100).status()).isEqualTo(201);
    }

    @Test
    void aLargePaymentIsHeldForReviewUntilAnAdminApprovesIt() {
        Merchant m = newMerchant();
        String customer = newAccount("whale", "CUSTOMER");
        deposit(customer, 30_000_000L);

        Resp created = createPaymentRaw(m, customer, 10_000_000L);      // exactly the default threshold
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("riskFlag").asText()).isEqualTo("REVIEW");
        String id = created.body().get("id").asText();

        assertThat(act(m, id, "authorize").status()).isEqualTo(409);    // blocked while under review

        Resp queue = admin("GET", "/admin/review-queue", null);
        assertThat(queue.status()).isEqualTo(200);
        boolean listed = false;
        for (var payment : queue.body()) {
            if (id.equals(payment.get("id").asText())) listed = true;
        }
        assertThat(listed).isTrue();

        // merchants cannot approve their own reviews
        assertThat(call("POST", "/admin/payments/" + id + "/approve", null,
                "Authorization", "Bearer " + m.apiKey()).status()).isEqualTo(401);

        Resp approved = admin("POST", "/admin/payments/" + id + "/approve", null);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("riskFlag").asText()).isEqualTo("APPROVED");
        assertThat(act(m, id, "authorize").status()).isEqualTo(200);
        assertThat(admin("POST", "/admin/payments/" + id + "/approve", null).status()).isEqualTo(409);
    }

    @Test
    void ordinaryPaymentsAreNotFlagged() {
        Merchant m = newMerchant();
        String customer = newAccount("regular", "CUSTOMER");

        Resp created = createPaymentRaw(m, customer, 12_000);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("riskFlag").asText()).isEqualTo("NONE");
    }
}
