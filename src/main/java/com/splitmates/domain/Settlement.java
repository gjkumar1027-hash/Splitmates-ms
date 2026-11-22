package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "settlements")
@Getter
@Setter
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long groupId;
    @Column(name = "from_member")
    private Long fromMember;
    @Column(name = "to_member")
    private Long toMember;
    private long amount;
    @Enumerated(EnumType.STRING)
    private SettlementMethod method;
    private LocalDate settledOn;
    @Enumerated(EnumType.STRING)
    private SettlementStatus status;
    private String note;
    @Column(name = "created_by_member")
    private Long createdByMember;
    private Instant createdAt = Instant.now();
    private Instant confirmedAt;
}
