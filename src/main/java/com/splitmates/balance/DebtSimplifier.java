package com.splitmates.balance;

import java.util.*;

/**
 * BR-05: greedy debt simplification. Repeatedly match the largest debtor with the largest
 * creditor and transfer the smaller of the two amounts. Produces at most n-1 payments.
 */
public final class DebtSimplifier {
    private DebtSimplifier() {
    }

    public record Transfer(Long from, Long to, long amount) {
    }

    private record Entry(Long memberId, long amount) {
    }

    public static List<Transfer> simplify(Map<Long, Long> nets) {
        long check = nets.values().stream().mapToLong(Long::longValue).sum();
        if (check != 0) throw new IllegalStateException("Balances do not reconcile to zero: " + check);

        Comparator<Entry> largestFirst = Comparator.comparingLong(Entry::amount).reversed().thenComparing(Entry::memberId);
        PriorityQueue<Entry> creditors = new PriorityQueue<>(largestFirst);
        PriorityQueue<Entry> debtors = new PriorityQueue<>(largestFirst);
        nets.forEach((m, v) -> {
            if (v > 0) creditors.add(new Entry(m, v));
            else if (v < 0) debtors.add(new Entry(m, -v));
        });

        List<Transfer> out = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Entry c = creditors.poll();
            Entry d = debtors.poll();
            long x = Math.min(c.amount(), d.amount());
            out.add(new Transfer(d.memberId(), c.memberId(), x));
            if (c.amount() > x) creditors.add(new Entry(c.memberId(), c.amount() - x));
            if (d.amount() > x) debtors.add(new Entry(d.memberId(), d.amount() - x));
        }
        return out;
    }
}
