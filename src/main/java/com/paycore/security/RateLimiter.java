package com.paycore.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * Distributed token-bucket rate limiter. State lives in Redis and the check-and-take
 * runs as one Lua script, so concurrent requests (even across several app servers)
 * cannot both grab the last token.
 * If Redis is unreachable we FAIL OPEN: better to briefly skip limiting than to take payments down.
 */
@Service
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    private final int capacity;
    private final double refillPerSecond;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${paycore.rate-limit.capacity}") int capacity,
                       @Value("${paycore.rate-limit.refill-per-second}") double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        script.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        script.setResultType(List.class);
    }

    public int getCapacity() {
        return capacity;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Decision tryAcquire(String identity) {
        try {
            List result = redis.execute(script, List.of("rl:" + identity),
                    String.valueOf(capacity), String.valueOf(refillPerSecond));
            if (result == null || result.size() < 3) return failOpen();
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            long remaining = ((Number) result.get(1)).longValue();
            long retryMs = ((Number) result.get(2)).longValue();
            return new Decision(allowed, remaining, Math.max(1, (retryMs + 999) / 1000));
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.toString());
            return failOpen();
        }
    }

    private Decision failOpen() {
        return new Decision(true, -1, 0);
    }
}
