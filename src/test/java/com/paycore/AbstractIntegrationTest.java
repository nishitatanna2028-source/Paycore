package com.paycore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Boots the whole application against REAL PostgreSQL and Redis containers (Testcontainers) and talks to it
 * over HTTP, exactly like a client would. The containers are started once per JVM and shared by all tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "paycore.webhooks.allow-private-targets=true",   // the test receivers listen on 127.0.0.1
        "paycore.webhooks.base-delay-seconds=1",
        "paycore.webhooks.max-attempts=5",
        "paycore.rate-limit.capacity=100000",             // rate limiting has its own check in scripts/concurrency_test.py
        "paycore.rate-limit.refill-per-second=100000",
        "paycore.fraud.velocity-max-per-minute=1000"      // FraudRulesTest lowers this again
})
public abstract class AbstractIntegrationTest {

    static final String ADMIN_KEY = "dev-admin-key";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    protected int port;

    protected final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    protected record Resp(int status, JsonNode body, String replayHeader) {}

    protected record Merchant(String id, String apiKey, String keyId) {}

    // ------------------------------------------------------------------ raw HTTP

    /** headers = alternating name, value, name, value... */
    protected Resp call(String method, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode node = (text == null || text.isBlank()) ? null : json.readTree(text);
            return new Resp(response.statusCode(), node, response.headers().firstValue("Idempotent-Replay").orElse(null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected Resp admin(String method, String path, Object body) {
        return call(method, path, body, "X-Admin-Key", ADMIN_KEY);
    }

    protected Resp adminWithKey(String method, String path, Object body, String idempotencyKey) {
        return call(method, path, body, "X-Admin-Key", ADMIN_KEY, "Idempotency-Key", idempotencyKey);
    }

    protected Resp merchantCall(Merchant m, String method, String path, Object body, String idempotencyKey) {
        if (idempotencyKey == null) {
            return call(method, path, body, "Authorization", "Bearer " + m.apiKey());
        }
        return call(method, path, body, "Authorization", "Bearer " + m.apiKey(), "Idempotency-Key", idempotencyKey);
    }

    // ------------------------------------------------------------------ domain helpers

    protected static String uuid() {
        return UUID.randomUUID().toString();
    }

    protected String newAccount(String name, String type) {
        Resp r = admin("POST", "/accounts", Map.of("name", name, "type", type));
        Assertions.assertEquals(201, r.status(), "creating account");
        return r.body().get("id").asText();
    }

    protected Merchant newMerchant() {
        String id = newAccount("merchant-" + uuid().substring(0, 8), "MERCHANT");
        Resp r = admin("POST", "/admin/merchants/" + id + "/api-keys", Map.of("name", "test"));
        Assertions.assertEquals(201, r.status(), "issuing api key");
        return new Merchant(id, r.body().get("apiKey").asText(), r.body().get("id").asText());
    }

    protected void deposit(String accountId, long amount) {
        Resp r = adminWithKey("POST", "/accounts/" + accountId + "/deposit", Map.of("amount", amount), uuid());
        Assertions.assertEquals(200, r.status(), "deposit");
    }

    protected long balance(String accountId) {
        return admin("GET", "/accounts/" + accountId, null).body().get("balance").asLong();
    }

    protected Resp createPaymentRaw(Merchant m, String customerId, long amount) {
        Map<String, Object> body = Map.of("customerId", customerId, "merchantId", m.id(), "amount", amount);
        return merchantCall(m, "POST", "/payments", body, uuid());
    }

    protected String createPayment(Merchant m, String customerId, long amount) {
        Resp r = createPaymentRaw(m, customerId, amount);
        Assertions.assertEquals(201, r.status(), "creating payment");
        return r.body().get("id").asText();
    }

    protected Resp act(Merchant m, String paymentId, String action) {
        return merchantCall(m, "POST", "/payments/" + paymentId + "/" + action, null, uuid());
    }

    // ------------------------------------------------------------------ concurrency helper

    /** Runs task(0..n-1) on n threads released at the same instant, and returns the results in order. */
    protected <T> List<T> runParallel(int n, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                Callable<T> job = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(pool.submit(job));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
