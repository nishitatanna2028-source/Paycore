package com.paycore.fraud;

import com.paycore.common.ApiException;
import com.paycore.domain.RiskFlag;
import com.paycore.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/**
 * Screens a new payment before it is created. Two rules:
 *   1. VELOCITY: more than N payment attempts by one customer inside 60 s -> DECLINED (422).
 *      Counted in Redis with a sliding window (atomic Lua), so it works across several app servers.
 *      Attempts that are declined still count, so a card-testing bot stays blocked while it keeps trying.
 *   2. LARGE AMOUNT: amount >= threshold -> payment is created but held for manual review.
 * Like the rate limiter, the velocity check FAILS OPEN if Redis is unreachable.
 */
@Service
public class FraudService {
    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    private static final long WINDOW_MS = 60_000;

    public record Assessment(RiskFlag flag, String reason) {
        public static Assessment clear() {
            return new Assessment(RiskFlag.NONE, null);
        }
    }

    private final StringRedisTemplate redis;
    private final BusinessMetrics metrics;
    private final DefaultRedisScript<Long> velocityScript = new DefaultRedisScript<>();
    private final boolean enabled;
    private final int velocityMaxPerMinute;
    private final long reviewAmount;

    public FraudService(StringRedisTemplate redis, BusinessMetrics metrics,
                        @Value("${paycore.fraud.enabled:true}") boolean enabled,
                        @Value("${paycore.fraud.velocity-max-per-minute:60}") int velocityMaxPerMinute,
                        @Value("${paycore.fraud.review-amount:10000000}") long reviewAmount) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
        this.velocityMaxPerMinute = velocityMaxPerMinute;
        this.reviewAmount = reviewAmount;
        velocityScript.setLocation(new ClassPathResource("redis/fraud_velocity.lua"));
        velocityScript.setResultType(Long.class);
    }

    /** Throws ApiException(422) to decline; otherwise returns whether the payment needs review. */
    public Assessment assess(UUID customerId, long amount) {
        if (!enabled) return Assessment.clear();

        long recent = recentAttempts(customerId);
        if (recent > velocityMaxPerMinute) {
            log.warn("Fraud: declined payment for customer {} ({} attempts in the last minute)", customerId, recent);
            metrics.fraudDeclined();
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Payment declined: too many payments from this customer in the last minute");
        }
        if (amount >= reviewAmount) {
            return new Assessment(RiskFlag.REVIEW,
                    "Amount " + amount + " is at or above the review threshold of " + reviewAmount);
        }
        return Assessment.clear();
    }

    private long recentAttempts(UUID customerId) {
        try {
            Long count = redis.execute(velocityScript, List.of("fraud:vel:" + customerId),
                    String.valueOf(WINDOW_MS), UUID.randomUUID().toString());
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("Fraud velocity check unavailable, failing open: {}", e.toString());
            return 0;
        }
    }
}
