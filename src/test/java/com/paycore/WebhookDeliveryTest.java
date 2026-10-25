package com.paycore;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebhookDeliveryTest extends AbstractIntegrationTest {

    private record Hit(String signature, String body) {}

    @Test
    void deliversSignedEventsAndRetriesUntilTheReceiverSucceeds() throws Exception {
        List<Hit> hits = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(new Hit(exchange.getRequestHeaders().getFirst("X-PayCore-Signature"), body));
            int status = calls.incrementAndGet() <= 2 ? 500 : 200;      // fail the first two deliveries
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Merchant m = newMerchant();
            String customer = newAccount("hook-customer", "CUSTOMER");
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";

            Resp endpoint = merchantCall(m, "POST", "/webhooks", Map.of("url", url), null);
            assertThat(endpoint.status()).isEqualTo(201);
            String secret = endpoint.body().get("secret").asText();
            assertThat(secret).startsWith("whsec_");

            String paymentId = createPayment(m, customer, 100);

            await().atMost(Duration.ofSeconds(40)).until(() -> calls.get() >= 3);

            // every attempt: same event, valid signature computed independently of the production code
            assertThat(hits).hasSizeGreaterThanOrEqualTo(3);
            String firstEventId = json.readTree(hits.get(0).body()).get("id").asText();
            for (Hit hit : hits) {
                assertThat(validSignature(secret, hit.signature(), hit.body())).isTrue();
                JsonNode event = json.readTree(hit.body());
                assertThat(event.get("id").asText()).isEqualTo(firstEventId);
                assertThat(event.get("type").asText()).isEqualTo("payment.created");
                assertThat(event.get("data").get("id").asText()).isEqualTo(paymentId);
            }

            // the event ends up DELIVERED after at least three attempts
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                Resp events = merchantCall(m, "GET", "/webhooks/events", null, null);
                return events.body() != null && events.body().size() == 1
                        && "DELIVERED".equals(events.body().get(0).get("status").asText())
                        && events.body().get(0).get("attempts").asInt() >= 3;
            });

            // a tampered signature must not verify
            assertThat(validSignature(secret, hits.get(0).signature(), hits.get(0).body() + " ")).isFalse();
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void anUnreachableEndpointEndsInTheDeadLetterQueueAndCanBeReplayed() throws Exception {
        Merchant m = newMerchant();
        String customer = newAccount("dlq-customer", "CUSTOMER");

        // reserve a port, then close it so nothing is listening there
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        Resp endpoint = merchantCall(m, "POST", "/webhooks",
                Map.of("url", "http://127.0.0.1:" + deadPort + "/hook"), null);
        assertThat(endpoint.status()).isEqualTo(201);
        createPayment(m, customer, 100);

        await().atMost(Duration.ofSeconds(60)).until(() -> {
            Resp dead = merchantCall(m, "GET", "/webhooks/events?status=DEAD", null, null);
            return dead.body() != null && dead.body().size() == 1;
        });
        Resp dead = merchantCall(m, "GET", "/webhooks/events?status=DEAD", null, null);
        String eventId = dead.body().get(0).get("id").asText();
        assertThat(dead.body().get(0).get("attempts").asInt()).isEqualTo(5);

        // replaying something that is not DEAD is refused, replaying a DEAD one is accepted
        assertThat(merchantCall(m, "POST", "/webhooks/events/" + eventId + "/replay", null, null).status())
                .isEqualTo(202);
        assertThat(merchantCall(m, "POST", "/webhooks/events/" + eventId + "/replay", null, null).status())
                .isEqualTo(409);    // it is PENDING again now
    }

    @Test
    void invalidWebhookUrlsAreRejected() {
        Merchant m = newMerchant();
        assertThat(merchantCall(m, "POST", "/webhooks", Map.of("url", "ftp://example.com/hook"), null).status())
                .isEqualTo(400);
        assertThat(merchantCall(m, "POST", "/webhooks", Map.of("url", "not a url"), null).status())
                .isEqualTo(400);
    }

    /** Independent re-implementation of the receiver-side check documented in the README. */
    private static boolean validSignature(String secret, String header, String body) throws Exception {
        String timestamp = null;
        String expected = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) timestamp = part.substring(2);
            if (part.startsWith("v1=")) expected = part.substring(3);
        }
        if (timestamp == null || expected == null) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String actual = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
