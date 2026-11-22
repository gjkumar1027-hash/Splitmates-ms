package com.splitmates.report;

import com.splitmates.domain.*;
import com.splitmates.group.GroupAccess;
import com.splitmates.repo.ExpenseRepository;
import com.splitmates.repo.GroupMemberRepository;
import com.splitmates.repo.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** FR-53 group insights + section 5.5 fun badges. */
@Service
public class InsightsService {
    public record CategoryAmount(Category category, long amount, int count) {
    }

    public record PersonAmount(Long memberId, long paid, long share) {
    }

    public record DayAmount(LocalDate date, long amount) {
    }

    public record Badge(String key, String title, String emoji, Long memberId, String detail) {
    }

    public record Insights(long totalSpend, int expenseCount, long days, Category topCategory,
                           List<CategoryAmount> byCategory, List<PersonAmount> byPerson, List<DayAmount> byDay,
                           List<Badge> badges) {
    }

    private final ExpenseRepository expenses;
    private final SettlementRepository settlements;
    private final GroupMemberRepository members;
    private final GroupAccess access;

    public InsightsService(ExpenseRepository expenses, SettlementRepository settlements, GroupMemberRepository members, GroupAccess access) {
        this.expenses = expenses;
        this.settlements = settlements;
        this.members = members;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Insights forGroup(Long groupId, Long userId) {
        access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        List<Expense> list = expenses.findByGroupIdAndDeletedFalseOrderByExpenseDateDescIdDesc(groupId);
        long total = list.stream().mapToLong(Expense::getAmount).sum();

        Map<Category, long[]> cat = new EnumMap<>(Category.class);
        Map<Long, long[]> person = new LinkedHashMap<>();
        TreeMap<LocalDate, Long> day = new TreeMap<>();
        Map<Long, Integer> foodPaidCount = new HashMap<>();
        for (Expense e : list) {
            long[] c = cat.computeIfAbsent(e.getCategory(), k -> new long[2]);
            c[0] += e.getAmount();
            c[1]++;
            day.merge(e.getExpenseDate(), e.getAmount(), Long::sum);
            for (ExpensePayer p : e.getPayers()) {
                person.computeIfAbsent(p.getMemberId(), k -> new long[2])[0] += p.getAmountPaid();
                if (e.getCategory() == Category.FOOD) foodPaidCount.merge(p.getMemberId(), 1, Integer::sum);
            }
            for (ExpenseShare s : e.getShares()) person.computeIfAbsent(s.getMemberId(), k -> new long[2])[1] += s.getAmountOwed();
        }
        List<CategoryAmount> byCat = cat.entrySet().stream()
                .map(x -> new CategoryAmount(x.getKey(), x.getValue()[0], (int) x.getValue()[1]))
                .sorted(Comparator.comparingLong(CategoryAmount::amount).reversed()).toList();
        List<PersonAmount> byPerson = person.entrySet().stream()
                .map(x -> new PersonAmount(x.getKey(), x.getValue()[0], x.getValue()[1]))
                .sorted(Comparator.comparingLong(PersonAmount::paid).reversed()).toList();
        List<DayAmount> byDay = day.entrySet().stream().map(x -> new DayAmount(x.getKey(), x.getValue())).toList();

        long days;
        if (g.getStartDate() != null && g.getEndDate() != null) days = ChronoUnit.DAYS.between(g.getStartDate(), g.getEndDate()) + 1;
        else days = day.size();

        Map<Long, String> names = members.findByGroupIdOrderByIdAsc(groupId).stream()
                .collect(Collectors.toMap(GroupMember::getId, GroupMember::getDisplayName));
        List<Badge> badges = new ArrayList<>();
        byPerson.stream().filter(p -> p.paid() > 0).findFirst().ifPresent(p ->
                badges.add(new Badge("BIGGEST_SPENDER", "Biggest spender", "🤑", p.memberId(), names.get(p.memberId()) + " paid the most")));
        foodPaidCount.entrySet().stream().max(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .ifPresent(x -> badges.add(new Badge("CHAI_SPONSOR", "Chai sponsor", "☕", x.getKey(),
                        names.get(x.getKey()) + " picked up " + x.getValue() + " food bill" + (x.getValue() > 1 ? "s" : ""))));
        // Fastest settler: first member to send a confirmed settlement in this group.
        settlements.findByGroupIdAndStatus(groupId, SettlementStatus.CONFIRMED).stream()
                .min(Comparator.comparing(Settlement::getCreatedAt))
                .ifPresent(x -> badges.add(new Badge("FASTEST_SETTLER", "Fastest settler", "⚡", x.getFromMember(),
                        names.get(x.getFromMember()) + " was first to settle up")));

        return new Insights(total, list.size(), days, byCat.isEmpty() ? null : byCat.getFirst().category(), byCat, byPerson, byDay, badges);
    }
}
