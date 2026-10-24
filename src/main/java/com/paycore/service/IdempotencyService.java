package com.paycore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs the business action and the idempotency record in ONE transaction.
 *
 * How it works: INSERT ... ON CONFLICT DO NOTHING on the key.
 *  - 1 row inserted -> we own this key: run the action, save the response, commit.
 *  - 0 rows         -> key already used: return the stored response (or 422 if the request differs).
 * If two identical requests race, Postgres makes the second INSERT wait until the first
 * transaction finishes, so exactly one of them executes the action.
 * If the action fails, the transaction rolls back (key row included), so the client can retry.
 */
@Service
public class IdempotencyService {
    public record Result(JsonNode body, boolean replayed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Result execute(String key, String fingerprint, Supplier<Object> action) {
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys (idem_key, fingerprint) VALUES (?, ?) " +
                "ON CONFLICT (idem_key) DO NOTHING", key, fingerprint);

        if (inserted == 0) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT fingerprint, response_body FROM idempotency_keys WHERE idem_key = ?", key);
            if (!fingerprint.equals(row.get("fingerprint"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Idempotency-Key was already used with a different request");
            }
            Object stored = row.get("response_body");
            if (stored == null) {
                throw new ApiException(HttpStatus.CONFLICT, "Original request is still in progress");
            }
            try {
                return new Result(mapper.readTree(stored.toString()), true);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupt stored response", e);
            }
        }

        Object result = action.get();
        JsonNode body = mapper.valueToTree(result);
        jdbc.update("UPDATE idempotency_keys SET response_body = ? WHERE idem_key = ?",
                body.toString(), key);
        return new Result(body, false);
    }
}
