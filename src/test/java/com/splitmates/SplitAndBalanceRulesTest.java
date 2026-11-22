package com.splitmates;

import com.splitmates.balance.DebtSimplifier;
import com.splitmates.balance.DebtSimplifier.Transfer;
import com.splitmates.common.Money;
import com.splitmates.domain.SplitType;
import com.splitmates.expense.SplitCalculator;
import com.splitmates.expense.SplitCalculator.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Business rules BR-01..BR-05 and acceptance criteria AC-01..AC-04 from the PRD. */
class SplitAndBalanceRulesTest {

    private static List<Line> equalLines(Long... ids) {
        return Arrays.stream(ids).map(id -> new Line(id, null)).toList();
    }

    private static Map<Long, Long> nets(Map<Long, Long> paid, Map<Long, Long> owed) {
        Map<Long, Long> n = new TreeMap<>();
        paid.forEach((k, v) -> n.merge(k, v, Long::sum));
        owed.forEach((k, v) -> n.merge(k, -v, Long::sum));
        return n;
    }

    @Test
    void ac01_equalSplitAmongFour() {
        Map<Long, Long> owed = SplitCalculator.split(100_000, SplitType.EQUAL, equalLines(1L, 2L, 3L, 4L), List.of(1L));
        assertThat(owed.values()).containsOnly(25_000L);
        Map<Long, Long> n = nets(Map.of(1L, 100_000L), owed);
        assertThat(n.get(1L)).isEqualTo(75_000L);
        assertThat(n.get(2L)).isEqualTo(-25_000L);
    }

    @Test
    void ac02_roundingLeftoverPaiseGoToPayerFirst() {
        Map<Long, Long> owed = SplitCalculator.split(10_000, SplitType.EQUAL, equalLines(1L, 2L, 3L), List.of(2L));
        assertThat(owed.get(2L)).isEqualTo(3_334L);
        assertThat(owed.get(1L)).isEqualTo(3_333L);
        assertThat(owed.get(3L)).isEqualTo(3_333L);
        assertThat(owed.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);
    }

    @Test
    void ac03_exactSplitMustSumToTotal() {
        List<Line> lines = List.of(new Line(1L, new BigDecimal("40.00")), new Line(2L, new BigDecimal("50.00")));
        assertThatThrownBy(() -> SplitCalculator.split(10_000, SplitType.EXACT, lines, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining");
    }

    @Test
    void percentMustSumTo100AndSharesAreExact() {
        assertThatThrownBy(() -> SplitCalculator.split(10_000, SplitType.PERCENT,
                List.of(new Line(1L, new BigDecimal("50")), new Line(2L, new BigDecimal("40"))), List.of(1L)))
                .hasMessageContaining("100%");
        Map<Long, Long> owed = SplitCalculator.split(10_000, SplitType.PERCENT,
                List.of(new Line(1L, new BigDecimal("33.33")), new Line(2L, new BigDecimal("33.33")), new Line(3L, new BigDecimal("33.34"))), List.of(1L));
        assertThat(owed.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);
    }

    @Test
    void sharesSplitCoupleCountsDouble() {
        Map<Long, Long> owed = SplitCalculator.split(90_000, SplitType.SHARES,
                List.of(new Line(1L, new BigDecimal("2")), new Line(2L, BigDecimal.ONE)), List.of(1L));
        assertThat(owed.get(1L)).isEqualTo(60_000L);
        assertThat(owed.get(2L)).isEqualTo(30_000L);
    }

    @Test
    void ac04_workedExampleNeedsExactlyTwoPayments() {
        // Arun(1) pays 6000 hotel, Divya(2) pays 1500 fuel; both split equally among 3.
        Map<Long, Long> hotel = SplitCalculator.split(600_000, SplitType.EQUAL, equalLines(1L, 2L, 3L), List.of(1L));
        Map<Long, Long> fuel = SplitCalculator.split(150_000, SplitType.EQUAL, equalLines(1L, 2L, 3L), List.of(2L));
        Map<Long, Long> owed = new HashMap<>(hotel);
        fuel.forEach((k, v) -> owed.merge(k, v, Long::sum));
        Map<Long, Long> n = nets(Map.of(1L, 600_000L, 2L, 150_000L), owed);
        assertThat(n).containsEntry(1L, 350_000L).containsEntry(2L, -100_000L).containsEntry(3L, -250_000L);

        List<Transfer> plan = DebtSimplifier.simplify(n);
        assertThat(plan).containsExactly(new Transfer(3L, 1L, 250_000L), new Transfer(2L, 1L, 100_000L));
    }

    @Test
    void br05_atMostNMinusOnePayments() {
        Random r = new Random(42);
        for (int run = 0; run < 200; run++) {
            int n = 2 + r.nextInt(30);
            Map<Long, Long> nets = new HashMap<>();
            long sum = 0;
            for (long i = 1; i < n; i++) {
                long v = r.nextLong(-500_000, 500_000);
                nets.put(i, v);
                sum += v;
            }
            nets.put((long) n, -sum);
            List<Transfer> plan = DebtSimplifier.simplify(nets);
            assertThat(plan.size()).isLessThanOrEqualTo(n - 1);
            Map<Long, Long> after = new HashMap<>(nets);
            for (Transfer t : plan) {
                after.merge(t.from(), t.amount(), Long::sum);
                after.merge(t.to(), -t.amount(), Long::sum);
            }
            assertThat(after.values()).containsOnly(0L);
        }
    }

    @Test
    void br01_moneyIsIntegerPaiseAndIndianFormat() {
        assertThat(Money.toPaise(new BigDecimal("4800"))).isEqualTo(480_000L);
        assertThat(Money.toPaise(new BigDecimal("0.10"))).isEqualTo(10L);
        assertThatThrownBy(() -> Money.toPaise(new BigDecimal("1.234"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.format(10_000_000L)).isEqualTo("₹1,00,000.00");
    }
}
