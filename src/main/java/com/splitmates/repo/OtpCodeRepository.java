package com.splitmates.repo;

import com.splitmates.domain.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {
    long countByPhoneAndCreatedAtAfter(String phone, Instant since);

    Optional<OtpCode> findFirstByPhoneAndConsumedFalseOrderByCreatedAtDesc(String phone);
}
