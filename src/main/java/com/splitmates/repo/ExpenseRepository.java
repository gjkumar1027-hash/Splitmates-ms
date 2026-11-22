package com.splitmates.repo;

import com.splitmates.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    List<Expense> findByGroupIdAndDeletedFalseOrderByExpenseDateDescIdDesc(Long groupId);

    List<Expense> findByGroupIdAndDeletedTrueAndDeletedAtAfterOrderByDeletedAtDesc(Long groupId, Instant after);

    long countByGroupIdAndDeletedFalse(Long groupId);

    /** [memberId, sum paid] over live expenses in the group. */
    @Query("""
            select p.memberId, sum(p.amountPaid) from ExpensePayer p
            where p.expense.groupId = :groupId and p.expense.deleted = false
            group by p.memberId""")
    List<Object[]> sumPaidByMember(Long groupId);

    /** [memberId, sum owed] over live expenses in the group. */
    @Query("""
            select s.memberId, sum(s.amountOwed) from ExpenseShare s
            where s.expense.groupId = :groupId and s.expense.deleted = false
            group by s.memberId""")
    List<Object[]> sumOwedByMember(Long groupId);

    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.groupId = :groupId and e.deleted = false")
    long totalSpend(Long groupId);
}
