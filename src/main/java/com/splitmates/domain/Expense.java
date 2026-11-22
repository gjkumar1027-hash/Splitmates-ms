package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "expenses")
@Getter
@Setter
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long groupId;
    private LocalDate expenseDate;
    private String purpose;
    /** Paise. */
    private long amount;
    @Enumerated(EnumType.STRING)
    private Category category;
    @Enumerated(EnumType.STRING)
    private SplitType splitType;
    private String notes;
    private String receiptPath;
    @Column(name = "created_by_member")
    private Long createdByMember;
    @Column(name = "updated_by_member")
    private Long updatedByMember;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Column(name = "is_deleted")
    private boolean deleted;
    private Instant deletedAt;
    @Version
    private int version;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ExpensePayer> payers = new ArrayList<>();

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ExpenseShare> shares = new ArrayList<>();
}
