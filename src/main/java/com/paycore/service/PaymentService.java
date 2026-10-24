package com.paycore.service;

import com.paycore.common.ApiException;
import com.paycore.common.SystemAccounts;
import com.paycore.domain.*;
import com.paycore.repo.AccountRepository;
import com.paycore.repo.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * Money flow per state:
 *   authorize: customer -> ESCROW      (funds held)
 *   capture:   ESCROW   -> merchant    (funds settled)
 *   void:      ESCROW   -> customer    (hold released)
 *   refund:    merchant -> customer    (after capture)
 * Lock order is always: payment row first, then account rows (sorted by id).
 */
@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final AccountRepository accounts;
    private final LedgerService ledger;

    public PaymentService(PaymentRepository payments, AccountRepository accounts, LedgerService ledger) {
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public Payment create(UUID customerId, UUID merchantId, long amount) {
        Account customer = accounts.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Customer not found"));
        Account merchant = accounts.findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Merchant not found"));
        if (customer.getType() != AccountType.CUSTOMER)
            throw new ApiException(HttpStatus.BAD_REQUEST, "customerId must be a CUSTOMER account");
        if (merchant.getType() != AccountType.MERCHANT)
            throw new ApiException(HttpStatus.BAD_REQUEST, "merchantId must be a MERCHANT account");
        return payments.save(new Payment(customerId, merchantId, amount));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    @Transactional
    public Payment authorize(UUID id) {
        Payment p = lock(id);
        p.moveTo(PaymentStatus.AUTHORIZED);
        ledger.transfer(p.getCustomerId(), SystemAccounts.ESCROW, p.getAmount(), "AUTHORIZE", p.getId());
        return p;
    }

    @Transactional
    public Payment capture(UUID id) {
        Payment p = lock(id);
        p.moveTo(PaymentStatus.CAPTURED);
        ledger.transfer(SystemAccounts.ESCROW, p.getMerchantId(), p.getAmount(), "CAPTURE", p.getId());
        return p;
    }

    @Transactional
    public Payment voidPayment(UUID id) {
        Payment p = lock(id);
        p.moveTo(PaymentStatus.VOIDED);
        ledger.transfer(SystemAccounts.ESCROW, p.getCustomerId(), p.getAmount(), "VOID", p.getId());
        return p;
    }

    @Transactional
    public Payment refund(UUID id) {
        Payment p = lock(id);
        p.moveTo(PaymentStatus.REFUNDED);
        ledger.transfer(p.getMerchantId(), p.getCustomerId(), p.getAmount(), "REFUND", p.getId());
        return p;
    }

    private Payment lock(UUID id) {
        return payments.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
    }
}
