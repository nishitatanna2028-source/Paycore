package com.paycore.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Signature scheme (same idea as Stripe):
 *   X-PayCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>
 * The receiver recomputes the HMAC over the raw bytes it received and rejects old timestamps,
 * which stops forged requests and replays of captured ones.
 */
public final class WebhookSigner {
    private WebhookSigner() {}

    public static String signature(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + signature(secret, timestamp, body);
    }
}
