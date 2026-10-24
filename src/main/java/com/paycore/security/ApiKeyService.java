package com.paycore.security;

import com.paycore.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, verifies and revokes merchant API keys.
 * Keys are 256 random bits, so a plain SHA-256 hash is enough to store them safely
 * (slow hashes like bcrypt are for low-entropy human passwords).
 */
@Service
public class ApiKeyService {
    public record IssuedKey(UUID id, String apiKey, String prefix) {}
    public record KeyInfo(UUID id, UUID merchantId) {}

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IssuedKey issue(UUID merchantId, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type FROM accounts WHERE id = ?", merchantId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Merchant not found");
        if (!"MERCHANT".equals(rows.get(0).get("type"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "API keys can only be issued to MERCHANT accounts");
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String apiKey = "pk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = apiKey.substring(0, 10);
        UUID id = UUID.randomUUID();

        jdbc.update("INSERT INTO api_keys (id, merchant_id, name, key_prefix, key_hash) VALUES (?, ?, ?, ?, ?)",
                id, merchantId, name, prefix, sha256(apiKey));
        return new IssuedKey(id, apiKey, prefix);
    }

    public Optional<KeyInfo> authenticate(String rawKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, merchant_id FROM api_keys WHERE key_hash = ? AND revoked = FALSE", sha256(rawKey));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        return Optional.of(new KeyInfo((UUID) row.get("id"), (UUID) row.get("merchant_id")));
    }

    @Transactional
    public void revoke(UUID keyId) {
        int updated = jdbc.update("UPDATE api_keys SET revoked = TRUE WHERE id = ?", keyId);
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "API key not found");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
