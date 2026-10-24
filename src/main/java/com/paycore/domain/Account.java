package com.paycore.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;
    private String name;
    @Enumerated(EnumType.STRING)
    private AccountType type;
    private long balance;
    private Instant createdAt;

    protected Account() {}

    public Account(String name, AccountType type) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.balance = 0;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public long getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }

    public void debit(long amount) { this.balance -= amount; }
    public void credit(long amount) { this.balance += amount; }
}
