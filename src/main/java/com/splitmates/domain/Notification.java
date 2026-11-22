package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Long groupId;
    private String type;
    private String message;
    private String link;
    @Column(name = "is_read")
    private boolean read;
    private Instant createdAt = Instant.now();
}
