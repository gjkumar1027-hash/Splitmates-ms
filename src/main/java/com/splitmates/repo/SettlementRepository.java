package com.splitmates.repo;

import com.splitmates.domain.Settlement;
import com.splitmates.domain.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    List<Settlement> findByGroupIdOrderByCreatedAtDesc(Long groupId);

    List<Settlement> findByGroupIdAndStatus(Long groupId, SettlementStatus status);

    List<Settlement> findByGroupIdAndStatusIn(Long groupId, Collection<SettlementStatus> statuses);
}
