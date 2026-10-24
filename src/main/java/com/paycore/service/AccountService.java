package com.paycore.service;

import com.paycore.common.ApiException;
import com.paycore.common.SystemAccounts;
import com.paycore.domain.Account;
import com.paycore.domain.AccountType;
import com.paycore.repo.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final LedgerService ledger;

    public AccountService(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public Account create(String name, AccountType type) {
        return accounts.save(new Account(name, type));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    @Transactional
    public Account deposit(UUID accountId, long amount) {
        Account target = get(accountId);
        if (target.getType() == AccountType.SYSTEM) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot deposit into a system account");
        }
        ledger.transfer(SystemAccounts.FUNDING, accountId, amount, "DEPOSIT", null);
        return get(accountId);
    }
}
