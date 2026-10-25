package com.paycore;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/** Correctness under concurrency, plus authentication and tenant isolation. */
class PaymentApiTest extends AbstractIntegrationTest {

    @Test
    void twentyParallelRequestsWithTheSameIdempotencyKeyCreateExactlyOnePayment() {
        Merchant m = newMerchant();
        String customer = newAccount("alice", "CUSTOMER");
        String key = uuid();
        Map<String, Object> body = Map.of("customerId", customer, "merchantId", m.id(), "amount", 100);

        List<Resp> results = runParallel(20, i -> merchantCall(m, "POST", "/payments", body, key));

        assertThat(results).allMatch(r -> r.status() == 201);
        Set<String> paymentIds = results.stream().map(r -> r.body().get("id").asText()).collect(Collectors.toSet());
        assertThat(paymentIds).hasSize(1);
        long replays = results.stream().filter(r -> "true".equals(r.replayHeader())).count();
        assertThat(replays).isEqualTo(19);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() {
        Merchant m = newMerchant();
        String customer = newAccount("alice", "CUSTOMER");
        String key = uuid();
        Resp first = merchantCall(m, "POST", "/payments",
                Map.of("customerId", customer, "merchantId", m.id(), "amount", 100), key);
        Resp second = merchantCall(m, "POST", "/payments",
                Map.of("customerId", customer, "merchantId", m.id(), "amount", 999), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(422);
    }

    @Test
    void parallelAuthorizationsNeverOverdrawTheCustomer() {
        Merchant m = newMerchant();
        String customer = newAccount("bob", "CUSTOMER");
        deposit(customer, 100);
        List<String> paymentIds = runParallel(30, i -> createPayment(m, customer, 10));

        List<Resp> results = runParallel(30, i -> act(m, paymentIds.get(i), "authorize"));

        long succeeded = results.stream().filter(r -> r.status() == 200).count();
        long rejected = results.stream().filter(r -> r.status() == 422).count();
        assertThat(succeeded).isEqualTo(10);
        assertThat(rejected).isEqualTo(20);
        assertThat(balance(customer)).isZero();
    }

    @Test
    void tenParallelCapturesMoveTheMoneyExactlyOnce() {
        Merchant m = newMerchant();
        String customer = newAccount("carol", "CUSTOMER");
        deposit(customer, 500);
        String paymentId = createPayment(m, customer, 200);
        assertThat(act(m, paymentId, "authorize").status()).isEqualTo(200);

        List<Resp> results = runParallel(10, i -> act(m, paymentId, "capture"));

        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 409).count()).isEqualTo(9);
        assertThat(balance(m.id())).isEqualTo(200);
        assertThat(balance(customer)).isEqualTo(300);
    }

    @Test
    void fullLifecycleAndIllegalTransitions() {
        Merchant m = newMerchant();
        String customer = newAccount("dave", "CUSTOMER");
        deposit(customer, 1000);
        String paymentId = createPayment(m, customer, 400);

        assertThat(act(m, paymentId, "refund").status()).isEqualTo(409);     // nothing captured yet
        assertThat(act(m, paymentId, "capture").status()).isEqualTo(409);    // not authorized yet
        assertThat(act(m, paymentId, "authorize").status()).isEqualTo(200);
        assertThat(balance(customer)).isEqualTo(600);
        assertThat(act(m, paymentId, "capture").status()).isEqualTo(200);
        assertThat(balance(m.id())).isEqualTo(400);
        assertThat(act(m, paymentId, "void").status()).isEqualTo(409);       // already captured
        assertThat(act(m, paymentId, "refund").status()).isEqualTo(200);
        assertThat(balance(customer)).isEqualTo(1000);
        assertThat(balance(m.id())).isZero();
    }

    @Test
    void voidReleasesTheHeldFunds() {
        Merchant m = newMerchant();
        String customer = newAccount("erin", "CUSTOMER");
        deposit(customer, 300);
        String paymentId = createPayment(m, customer, 300);
        act(m, paymentId, "authorize");
        assertThat(balance(customer)).isZero();

        assertThat(act(m, paymentId, "void").status()).isEqualTo(200);

        assertThat(balance(customer)).isEqualTo(300);
    }

    @Test
    void authenticationIsEnforced() {
        Merchant m = newMerchant();
        String customer = newAccount("frank", "CUSTOMER");
        Map<String, Object> body = Map.of("customerId", customer, "merchantId", m.id(), "amount", 100);

        assertThat(call("POST", "/payments", body, "Idempotency-Key", uuid()).status()).isEqualTo(401);
        assertThat(call("POST", "/payments", body, "Authorization", "Bearer pk_not_a_real_key",
                "Idempotency-Key", uuid()).status()).isEqualTo(401);
        assertThat(call("POST", "/accounts", Map.of("name", "x", "type", "CUSTOMER")).status()).isEqualTo(401);
        assertThat(call("GET", "/admin/reconcile", null, "Authorization", "Bearer " + m.apiKey()).status())
                .isEqualTo(401);
    }

    @Test
    void merchantsCannotSeeOrTouchEachOthersPayments() {
        Merchant a = newMerchant();
        Merchant b = newMerchant();
        String customer = newAccount("gina", "CUSTOMER");
        deposit(customer, 500);
        String paymentOfA = createPayment(a, customer, 100);

        assertThat(merchantCall(b, "GET", "/payments/" + paymentOfA, null, null).status()).isEqualTo(404);
        assertThat(act(b, paymentOfA, "authorize").status()).isEqualTo(404);
        assertThat(merchantCall(b, "POST", "/payments",
                Map.of("customerId", customer, "merchantId", a.id(), "amount", 5), uuid()).status()).isEqualTo(403);
        assertThat(merchantCall(a, "GET", "/payments/" + paymentOfA, null, null).status()).isEqualTo(200);
    }

    @Test
    void theSameIdempotencyKeyFromTwoMerchantsCreatesTwoSeparatePayments() {
        Merchant a = newMerchant();
        Merchant b = newMerchant();
        String customer = newAccount("hank", "CUSTOMER");
        String sharedKey = "shared-" + uuid();

        Resp ra = merchantCall(a, "POST", "/payments",
                Map.of("customerId", customer, "merchantId", a.id(), "amount", 7), sharedKey);
        Resp rb = merchantCall(b, "POST", "/payments",
                Map.of("customerId", customer, "merchantId", b.id(), "amount", 7), sharedKey);

        assertThat(ra.status()).isEqualTo(201);
        assertThat(rb.status()).isEqualTo(201);
        assertThat(ra.body().get("id").asText()).isNotEqualTo(rb.body().get("id").asText());
    }

    @Test
    void revokedApiKeysStopWorking() {
        Merchant m = newMerchant();
        assertThat(merchantCall(m, "GET", "/payments/" + uuid(), null, null).status()).isEqualTo(404);

        assertThat(admin("DELETE", "/admin/api-keys/" + m.keyId(), null).status()).isEqualTo(204);

        assertThat(merchantCall(m, "GET", "/payments/" + uuid(), null, null).status()).isEqualTo(401);
    }

    @Test
    void theBooksBalanceAfterAllThatActivity() {
        Merchant m = newMerchant();
        String customer = newAccount("ivy", "CUSTOMER");
        deposit(customer, 5000);
        runParallel(20, i -> {
            String id = createPayment(m, customer, 50);
            act(m, id, "authorize");
            return act(m, id, i % 2 == 0 ? "capture" : "void").status();
        });

        Resp report = admin("GET", "/admin/reconcile", null);

        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().get("healthy").asBoolean()).isTrue();
        assertThat(report.body().get("totalDebits").asLong()).isEqualTo(report.body().get("totalCredits").asLong());
    }
}
