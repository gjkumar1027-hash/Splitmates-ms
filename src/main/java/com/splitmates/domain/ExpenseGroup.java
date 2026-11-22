package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "expense_groups")
@Getter
@Setter
public class ExpenseGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private GroupType type;
    private String coverEmoji;
    private String coverColor;
    private LocalDate startDate;
    private LocalDate endDate;
    private String currency = "INR";
    @Enumerated(EnumType.STRING)
    private GroupStatus status = GroupStatus.ACTIVE;
    private String inviteCode;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant archivedAt;

    public boolean isArchived() {
        return status == GroupStatus.ARCHIVED;
    }
}
