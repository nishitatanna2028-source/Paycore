package com.paycore.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.paycore.common.ApiException;
import com.paycore.domain.Account;
import com.paycore.domain.AccountType;
import com.paycore.domain.LedgerEntry;
import com.paycore.domain.Payment;
import com.paycore.repo.LedgerRepository;
import com.paycore.service.AccountService;
import com.paycore.service.IdempotencyService;
import com.paycore.service.PaymentService;
import com.paycore.service.ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
public class ApiController {

    public record CreateAccountRequest(@NotBlank String name, @NotNull AccountType type) {}
    public record DepositRequest(@Positive long amount) {}
    public record CreatePaymentRequest(@NotNull UUID customerId, @NotNull UUID merchantId, @Positive long amount) {}

    private final AccountService accountService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotency;
    private final LedgerRepository ledgerRepo;
    private final ReconciliationService reconciliation;

    public ApiController(AccountService accountService, PaymentService paymentService,
                         IdempotencyService idempotency, LedgerRepository ledgerRepo,
                         ReconciliationService reconciliation) {
        this.accountService = accountService;
        this.paymentService = paymentService;
        this.idempotency = idempotency;
        this.ledgerRepo = ledgerRepo;
        this.reconciliation = reconciliation;
    }

    // ---------- accounts ----------

    @PostMapping("/accounts")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        if (req.type() == AccountType.SYSTEM) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Type must be CUSTOMER or MERCHANT");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(req.name(), req.type()));
    }

    @GetMapping("/accounts/{id}")
    public Account getAccount(@PathVariable UUID id) {
        return accountService.get(id);
    }

    @GetMapping("/accounts/{id}/ledger")
    public List<LedgerEntry> ledger(@PathVariable UUID id) {
        accountService.get(id); // 404 if missing
        return ledgerRepo.findByAccountIdOrderByIdAsc(id);
    }

    @PostMapping("/accounts/{id}/deposit")
    public ResponseEntity<JsonNode> deposit(@PathVariable UUID id,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody DepositRequest req) {
        return idempotent(key, fingerprint("POST /accounts/" + id + "/deposit", req), HttpStatus.OK,
                () -> accountService.deposit(id, req.amount()));
    }

    // ---------- payments ----------

    @PostMapping("/payments")
    public ResponseEntity<JsonNode> createPayment(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody CreatePaymentRequest req) {
        return idempotent(key, fingerprint("POST /payments", req), HttpStatus.CREATED,
                () -> paymentService.create(req.customerId(), req.merchantId(), req.amount()));
    }

    @GetMapping("/payments/{id}")
    public Payment getPayment(@PathVariable UUID id) {
        return paymentService.get(id);
    }

    @PostMapping("/payments/{id}/authorize")
    public ResponseEntity<JsonNode> authorize(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotent(key, fingerprint("POST /payments/" + id + "/authorize", ""), HttpStatus.OK,
                () -> paymentService.authorize(id));
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<JsonNode> capture(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotent(key, fingerprint("POST /payments/" + id + "/capture", ""), HttpStatus.OK,
                () -> paymentService.capture(id));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<JsonNode> voidPayment(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotent(key, fingerprint("POST /payments/" + id + "/void", ""), HttpStatus.OK,
                () -> paymentService.voidPayment(id));
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<JsonNode> refund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotent(key, fingerprint("POST /payments/" + id + "/refund", ""), HttpStatus.OK,
                () -> paymentService.refund(id));
    }

    // ---------- admin ----------

    @GetMapping("/admin/reconcile")
    public Map<String, Object> reconcile() {
        return reconciliation.run();
    }

    // ---------- helpers ----------

    private ResponseEntity<JsonNode> idempotent(String key, String fingerprint, HttpStatus status,
                                                Supplier<Object> action) {
        if (key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-100 characters");
        }
        IdempotencyService.Result r = idempotency.execute(key, fingerprint, action);
        return ResponseEntity.status(status)
                .header("Idempotent-Replay", String.valueOf(r.replayed()))
                .body(r.body());
    }

    private static String fingerprint(String route, Object body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((route + "|" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
