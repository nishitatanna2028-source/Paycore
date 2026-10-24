package com.paycore.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID transactionId;
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    private long amount;
    private String reason;
    private UUID paymentId;
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(UUID transactionId, UUID accountId, Direction direction,
                       long amount, String reason, UUID paymentId) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.reason = reason;
        this.paymentId = paymentId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public long getAmount() { return amount; }
    public String getReason() { return reason; }
    public UUID getPaymentId() { return paymentId; }
    public Instant getCreatedAt() { return createdAt; }
}
