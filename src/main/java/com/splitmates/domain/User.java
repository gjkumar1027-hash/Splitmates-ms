package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String displayName;
    private String phone;
    private String email;
    private String avatarEmoji;
    private String avatarColor = "#7C3AED";
    private String upiId;
    @Enumerated(EnumType.STRING)
    private Theme theme = Theme.SYSTEM;
    private boolean notifyInApp = true;
    private boolean notifyWeekly = false;
    private boolean showPhone = false;
    private Instant consentAt;
    private boolean deleted = false;
    private Instant createdAt = Instant.now();
}
