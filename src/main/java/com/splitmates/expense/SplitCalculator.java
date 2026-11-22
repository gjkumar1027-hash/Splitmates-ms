package com.splitmates.expense;

import com.splitmates.common.Money;
import com.splitmates.domain.SplitType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Pure split maths in integer paise (BR-01..BR-03). No Spring, no DB — unit tested directly.
 */
public final class SplitCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SplitCalculator() {
    }

    public record Line(Long memberId, BigDecimal value) {
    }

    /**
     * @param total        amount in paise
     * @param type         split method
     * @param participants ordered participants with their raw input (null for EQUAL)
     * @param payerOrder   payer member ids; leftover paise go to payers first (BR-02)
     * @return memberId -> amount owed (paise), summing exactly to total
     */
    public static LinkedHashMap<Long, Long> split(long total, SplitType type, List<Line> participants, List<Long> payerOrder) {
        if (total <= 0) throw new IllegalArgumentException("Amount must be greater than 0");
        if (participants == null || participants.isEmpty()) throw new IllegalArgumentException("Pick at least one participant");
        Set<Long> seen = new HashSet<>();
        for (Line l : participants) {
            if (!seen.add(l.memberId())) throw new IllegalArgumentException("A participant appears twice");
        }
        List<Line> ordered = leftoverOrder(participants, payerOrder);

        return switch (type) {
            case EQUAL -> equal(total, ordered);
            case EXACT -> exact(total, participants);
            case PERCENT -> weighted(total, ordered, requireSum(ordered, HUNDRED, "Percentages must add up to 100%"));
            case SHARES -> weighted(total, ordered, positiveSum(ordered));
        };
    }

    /** Payers first, then everyone else in the given order. */
    private static List<Line> leftoverOrder(List<Line> participants, List<Long> payerOrder) {
        List<Line> out = new ArrayList<>();
        Map<Long, Line> byId = new LinkedHashMap<>();
        participants.forEach(l -> byId.put(l.memberId(), l));
        if (payerOrder != null) {
            for (Long p : payerOrder) {
                Line l = byId.remove(p);
                if (l != null) out.add(l);
            }
        }
        out.addAll(byId.values());
        return out;
    }

    private static LinkedHashMap<Long, Long> equal(long total, List<Line> ordered) {
        int n = ordered.size();
        long base = total / n;
        long rem = total % n;
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) out.put(ordered.get(i).memberId(), base + (i < rem ? 1 : 0));
        return out;
    }

    private static LinkedHashMap<Long, Long> exact(long total, List<Line> lines) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        long sum = 0;
        for (Line l : lines) {
            if (l.value() == null || l.value().signum() < 0) throw new IllegalArgumentException("Enter an amount for every participant");
            long p = Money.toPaise(l.value());
            out.put(l.memberId(), p);
            sum += p;
        }
        if (sum != total) {
            throw new IllegalArgumentException("Split amounts add up to " + Money.format(sum) + " but the total is " + Money.format(total)
                    + " (" + Money.format(total - sum) + " remaining)");
        }
        return out;
    }

    private static BigDecimal requireSum(List<Line> lines, BigDecimal expected, String message) {
        BigDecimal sum = positiveSum(lines);
        if (sum.compareTo(expected) != 0) throw new IllegalArgumentException(message + " (currently " + sum.stripTrailingZeros().toPlainString() + ")");
        return sum;
    }

    private static BigDecimal positiveSum(List<Line> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.value() == null || l.value().signum() < 0) throw new IllegalArgumentException("Enter a value for every participant");
            sum = sum.add(l.value());
        }
        if (sum.signum() <= 0) throw new IllegalArgumentException("At least one participant needs a share");
        return sum;
    }

    /** Proportional split: floor each share, then hand out leftover paise one each in fixed order. */
    private static LinkedHashMap<Long, Long> weighted(long total, List<Line> ordered, BigDecimal weightSum) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        long allocated = 0;
        BigDecimal t = BigDecimal.valueOf(total);
        for (Line l : ordered) {
            long p = t.multiply(l.value()).divide(weightSum, 0, RoundingMode.FLOOR).longValueExact();
            out.put(l.memberId(), p);
            allocated += p;
        }
        long rem = total - allocated;
        for (Line l : ordered) {
            if (rem == 0) break;
            if (l.value().signum() > 0) {
                out.merge(l.memberId(), 1L, Long::sum);
                rem--;
            }
        }
        return out;
    }
}
