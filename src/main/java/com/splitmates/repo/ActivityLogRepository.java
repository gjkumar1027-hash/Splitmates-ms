package com.splitmates.repo;

import com.splitmates.domain.ActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);

    List<ActivityLog> findByGroupIdInOrderByCreatedAtDesc(Collection<Long> groupIds, Pageable pageable);
}
