package com.paycore.service;

import com.paycore.common.ApiException;
import com.paycore.common.SystemAccounts;
import com.paycore.domain.*;
import com.paycore.repo.AccountRepository;
import com.paycore.repo.PaymentRepository;
import com.paycore.webhook.WebhookService;
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
 * Each state change also writes a webhook event to the outbox IN THE SAME TRANSACTION.
 */
@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final WebhookService webhooks;

    public PaymentService(PaymentRepository payments, AccountRepository accounts,
                          LedgerService ledger, WebhookService webhooks) {
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.webhooks = webhooks;
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
        Payment saved = payments.save(new Payment(customerId, merchantId, amount));
        webhooks.enqueue(merchantId, "payment.created", saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment getOwned(UUID id, UUID merchantId) {
        Payment p = payments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
        assertOwner(p, merchantId);
        return p;
    }

    @Transactional
    public Payment authorize(UUID id, UUID merchantId) {
        Payment p = lock(id);
        assertOwner(p, merchantId);
        p.moveTo(PaymentStatus.AUTHORIZED);
        ledger.transfer(p.getCustomerId(), SystemAccounts.ESCROW, p.getAmount(), "AUTHORIZE", p.getId());
        webhooks.enqueue(p.getMerchantId(), "payment.authorized", p);
        return p;
    }

    @Transactional
    public Payment capture(UUID id, UUID merchantId) {
        Payment p = lock(id);
        assertOwner(p, merchantId);
        p.moveTo(PaymentStatus.CAPTURED);
        ledger.transfer(SystemAccounts.ESCROW, p.getMerchantId(), p.getAmount(), "CAPTURE", p.getId());
        webhooks.enqueue(p.getMerchantId(), "payment.captured", p);
        return p;
    }

    @Transactional
    public Payment voidPayment(UUID id, UUID merchantId) {
        Payment p = lock(id);
        assertOwner(p, merchantId);
        p.moveTo(PaymentStatus.VOIDED);
        ledger.transfer(SystemAccounts.ESCROW, p.getCustomerId(), p.getAmount(), "VOID", p.getId());
        webhooks.enqueue(p.getMerchantId(), "payment.voided", p);
        return p;
    }

    @Transactional
    public Payment refund(UUID id, UUID merchantId) {
        Payment p = lock(id);
        assertOwner(p, merchantId);
        p.moveTo(PaymentStatus.REFUNDED);
        ledger.transfer(p.getMerchantId(), p.getCustomerId(), p.getAmount(), "REFUND", p.getId());
        webhooks.enqueue(p.getMerchantId(), "payment.refunded", p);
        return p;
    }

    /** Another merchant's payment looks exactly like a payment that doesn't exist. */
    private void assertOwner(Payment p, UUID merchantId) {
        if (!p.getMerchantId().equals(merchantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Payment not found");
        }
    }

    private Payment lock(UUID id) {
        return payments.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
    }
}
