package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "otp_codes")
@Getter
@Setter
public class OtpCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String phone;
    private String codeHash;
    private int attempts;
    private boolean consumed;
    private Instant expiresAt;
    private Instant createdAt = Instant.now();
}
