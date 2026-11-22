package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "nudges")
@Getter
@Setter
public class Nudge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long groupId;
    @Column(name = "from_member")
    private Long fromMember;
    @Column(name = "to_member")
    private Long toMember;
    private Instant createdAt = Instant.now();
}
