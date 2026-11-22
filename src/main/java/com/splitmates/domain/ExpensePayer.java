package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "expense_payers")
@Getter
@Setter
@NoArgsConstructor
public class ExpensePayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id")
    private Expense expense;
    private Long memberId;
    private long amountPaid;

    public ExpensePayer(Expense expense, Long memberId, long amountPaid) {
        this.expense = expense;
        this.memberId = memberId;
        this.amountPaid = amountPaid;
    }
}
