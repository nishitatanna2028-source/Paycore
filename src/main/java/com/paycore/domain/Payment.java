package com.paycore.domain;

import com.paycore.common.ApiException;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private UUID id;
    private UUID customerId;
    private UUID merchantId;
    private long amount;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {}

    public Payment(UUID customerId, UUID merchantId, long amount) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void moveTo(PaymentStatus next) {
        if (!status.canMoveTo(next)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Illegal transition: " + status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getMerchantId() { return merchantId; }
    public long getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
