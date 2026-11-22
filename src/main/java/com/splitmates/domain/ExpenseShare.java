package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "expense_shares")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id")
    private Expense expense;
    private Long memberId;
    /** Raw input: percentage, number of shares or exact rupees depending on split type. */
    private BigDecimal shareValue;
    private long amountOwed;

    public ExpenseShare(Expense expense, Long memberId, BigDecimal shareValue, long amountOwed) {
        this.expense = expense;
        this.memberId = memberId;
        this.shareValue = shareValue;
        this.amountOwed = amountOwed;
    }
}
