package com.splitmates.repo;

import com.splitmates.domain.Nudge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface NudgeRepository extends JpaRepository<Nudge, Long> {
    boolean existsByFromMemberAndToMemberAndCreatedAtAfter(Long fromMember, Long toMember, Instant after);
}
