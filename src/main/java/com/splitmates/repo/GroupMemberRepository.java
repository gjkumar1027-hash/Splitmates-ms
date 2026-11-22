package com.splitmates.repo;

import com.splitmates.domain.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByGroupIdOrderByIdAsc(Long groupId);

    List<GroupMember> findByGroupIdInOrderByIdAsc(Collection<Long> groupIds);

    Optional<GroupMember> findByGroupIdAndUserIdAndActiveTrue(Long groupId, Long userId);

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    List<GroupMember> findByUserId(Long userId);

    long countByGroupIdAndActiveTrue(Long groupId);
}
