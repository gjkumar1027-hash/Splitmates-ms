package com.splitmates.repo;

import com.splitmates.domain.ExpenseGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GroupRepository extends JpaRepository<ExpenseGroup, Long> {
    Optional<ExpenseGroup> findByInviteCode(String inviteCode);

    @Query("""
            select g from ExpenseGroup g where g.id in
              (select m.groupId from GroupMember m where m.userId = :userId and m.active = true)
            order by g.status asc, g.createdAt desc""")
    List<ExpenseGroup> findAllForUser(Long userId);
}
