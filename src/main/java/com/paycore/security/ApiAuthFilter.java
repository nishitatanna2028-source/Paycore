package com.paycore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Runs before every controller:
 *   1. /payments/**            -> needs a merchant API key   (Authorization: Bearer pk_...)
 *   2. everything else         -> needs the admin key         (X-Admin-Key: ...)
 *   3. then rate-limits per API key (or per admin)
 * Swagger/OpenAPI pages are public.
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {
    public static final String ATTR = "paycore.auth";

    private final ApiKeyService apiKeys;
    private final RateLimiter rateLimiter;
    private final byte[] adminKey;

    public ApiAuthFilter(ApiKeyService apiKeys, RateLimiter rateLimiter,
                         @Value("${paycore.admin-key}") String adminKey) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        AuthContext auth;
        String identity;

        if (path.startsWith("/payments")) {
            String rawKey = bearerToken(req);
            Optional<ApiKeyService.KeyInfo> info = rawKey == null ? Optional.empty() : apiKeys.authenticate(rawKey);
            if (info.isEmpty()) {
                reject(res, 401, "Missing or invalid API key. Send 'Authorization: Bearer <key>'.");
                return;
            }
            auth = AuthContext.merchant(info.get().merchantId());
            identity = "key:" + info.get().id();
        } else {
            String provided = req.getHeader("X-Admin-Key");
            // constant-time comparison so response timing can't leak the key
            if (provided == null || !MessageDigest.isEqual(adminKey, provided.getBytes(StandardCharsets.UTF_8))) {
                reject(res, 401, "Missing or invalid admin key. Send 'X-Admin-Key: <key>'.");
                return;
            }
            auth = AuthContext.admin();
            identity = "admin";
        }

        RateLimiter.Decision decision = rateLimiter.tryAcquire(identity);
        res.setHeader("X-RateLimit-Limit", String.valueOf(rateLimiter.getCapacity()));
        if (decision.remaining() >= 0) {
            res.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (!decision.allowed()) {
            res.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            reject(res, 429, "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + "s.");
            return;
        }

        req.setAttribute(ATTR, auth);
        chain.doFilter(req, res);
    }

    private static boolean isPublic(String path) {
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.equals("/favicon.ico");
    }

    private static String bearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
