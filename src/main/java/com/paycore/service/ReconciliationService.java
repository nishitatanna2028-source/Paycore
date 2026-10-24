package com.paycore.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies the books: stored balances must equal what the ledger says, and debits must equal credits. */
@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // REPEATABLE_READ so all three queries see the same snapshot even while payments are flowing.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> run() {
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                SELECT id, name, balance, ledger_balance FROM (
                    SELECT a.id, a.name, a.balance,
                           COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END), 0)::bigint AS ledger_balance
                    FROM accounts a LEFT JOIN ledger_entries l ON l.account_id = a.id
                    GROUP BY a.id, a.name, a.balance
                ) t WHERE balance <> ledger_balance
                """);
        Long debits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0)::bigint FROM ledger_entries WHERE direction = 'DEBIT'", Long.class);
        Long credits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0)::bigint FROM ledger_entries WHERE direction = 'CREDIT'", Long.class);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("healthy", mismatches.isEmpty() && debits.equals(credits));
        report.put("totalDebits", debits);
        report.put("totalCredits", credits);
        report.put("mismatchedAccounts", mismatches);
        return report;
    }
}
