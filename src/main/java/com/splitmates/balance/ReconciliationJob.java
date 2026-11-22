package com.splitmates.balance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** NFR-05: nightly reconciliation that flags any drift in stored money data. */
@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private final JdbcTemplate jdbc;

    public ReconciliationJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    public void run() {
        List<Long> bad = jdbc.queryForList("""
                select e.id from expenses e
                where e.is_deleted = false and (
                  e.amount <> (select coalesce(sum(p.amount_paid), 0) from expense_payers p where p.expense_id = e.id)
                  or e.amount <> (select coalesce(sum(s.amount_owed), 0) from expense_shares s where s.expense_id = e.id))
                """, Long.class);
        if (bad.isEmpty()) {
            log.info("Reconciliation OK: all expenses balance (payers = shares = amount)");
        } else {
            log.error("RECONCILIATION DRIFT: {} expense(s) do not balance: {}", bad.size(), bad);
        }
    }
}
