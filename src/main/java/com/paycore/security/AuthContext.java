package com.paycore.security;

import java.util.UUID;

/** Who is calling. Set on the request by ApiAuthFilter after the key is verified. */
public record AuthContext(String scope, UUID merchantId) {
    public static AuthContext admin() {
        return new AuthContext("admin", null);
    }

    public static AuthContext merchant(UUID merchantId) {
        return new AuthContext(merchantId.toString(), merchantId);
    }
}
