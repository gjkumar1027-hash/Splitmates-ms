package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "activity_log")
@Getter
@Setter
public class ActivityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long groupId;
    @Column(name = "actor_member")
    private Long actorMember;
    private String action;
    private String entityType;
    private Long entityId;
    private String summary;
    private String beforeJson;
    private String afterJson;
    private Instant createdAt = Instant.now();
}
