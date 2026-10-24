package com.paycore.service;

import com.paycore.common.ApiException;
import com.paycore.domain.*;
import com.paycore.repo.AccountRepository;
import com.paycore.repo.LedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place money moves. Every transfer writes one DEBIT and one CREDIT entry
 * and updates both balances inside the caller's transaction.
 */
@Service
public class LedgerService {
    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    // MANDATORY: refuses to run outside a transaction, so a half-done transfer is impossible.
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(UUID from, UUID to, long amount, String reason, UUID paymentId) {
        if (amount <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        if (from.equals(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot transfer to the same account");

        // Lock rows in a fixed (sorted) order so two opposite transfers can't deadlock each other.
        List<UUID> order = from.compareTo(to) < 0 ? List.of(from, to) : List.of(to, from);
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : order) {
            locked.put(id, accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + id)));
        }
        Account src = locked.get(from);
        Account dst = locked.get(to);

        if (src.getType() != AccountType.SYSTEM && src.getBalance() < amount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
        }

        src.debit(amount);
        dst.credit(amount);

        UUID txId = UUID.randomUUID();
        ledger.save(new LedgerEntry(txId, from, Direction.DEBIT, amount, reason, paymentId));
        ledger.save(new LedgerEntry(txId, to, Direction.CREDIT, amount, reason, paymentId));
    }
}
