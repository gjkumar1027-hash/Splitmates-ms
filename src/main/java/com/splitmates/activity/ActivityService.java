package com.splitmates.activity;

import com.splitmates.domain.ActivityLog;
import com.splitmates.domain.ExpenseGroup;
import com.splitmates.domain.GroupMember;
import com.splitmates.repo.ActivityLogRepository;
import com.splitmates.repo.GroupMemberRepository;
import com.splitmates.repo.GroupRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** FR-52 activity history: every add/edit/delete/settle is recorded with before/after snapshots. */
@Service
public class ActivityService {
    public record ActivityDto(Long id, Long groupId, String groupName, String groupEmoji, Long actorMemberId,
                              String actorName, String actorEmoji, String actorColor, String action,
                              String entityType, Long entityId, String summary, Instant createdAt) {
    }

    private final ActivityLogRepository repo;
    private final GroupMemberRepository members;
    private final GroupRepository groups;
    private final ObjectMapper json;

    public ActivityService(ActivityLogRepository repo, GroupMemberRepository members, GroupRepository groups, ObjectMapper json) {
        this.repo = repo;
        this.members = members;
        this.groups = groups;
        this.json = json;
    }

    @Transactional
    public void log(Long groupId, Long actorMemberId, String action, String entityType, Long entityId, String summary,
                    Object before, Object after) {
        ActivityLog a = new ActivityLog();
        a.setGroupId(groupId);
        a.setActorMember(actorMemberId);
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setSummary(summary.length() > 300 ? summary.substring(0, 297) + "..." : summary);
        a.setBeforeJson(before == null ? null : json.writeValueAsString(before));
        a.setAfterJson(after == null ? null : json.writeValueAsString(after));
        repo.save(a);
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> forGroup(Long groupId, int limit) {
        return toDtos(repo.findByGroupIdOrderByCreatedAtDesc(groupId, PageRequest.of(0, limit)));
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> forUser(Long userId, int limit) {
        Set<Long> groupIds = members.findByUserId(userId).stream().map(GroupMember::getGroupId).collect(Collectors.toSet());
        if (groupIds.isEmpty()) return List.of();
        return toDtos(repo.findByGroupIdInOrderByCreatedAtDesc(groupIds, PageRequest.of(0, limit)));
    }

    private List<ActivityDto> toDtos(List<ActivityLog> logs) {
        Set<Long> memberIds = logs.stream().map(ActivityLog::getActorMember).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, GroupMember> ms = members.findAllById(memberIds).stream().collect(Collectors.toMap(GroupMember::getId, Function.identity()));
        Set<Long> gids = logs.stream().map(ActivityLog::getGroupId).collect(Collectors.toSet());
        Map<Long, ExpenseGroup> gs = groups.findAllById(gids).stream().collect(Collectors.toMap(ExpenseGroup::getId, Function.identity()));
        return logs.stream().map(a -> {
            GroupMember m = ms.get(a.getActorMember());
            ExpenseGroup g = gs.get(a.getGroupId());
            return new ActivityDto(a.getId(), a.getGroupId(), g == null ? null : g.getName(), g == null ? null : g.getCoverEmoji(),
                    a.getActorMember(), m == null ? "Someone" : m.getDisplayName(), m == null ? null : m.getAvatarEmoji(),
                    m == null ? null : m.getAvatarColor(), a.getAction(), a.getEntityType(), a.getEntityId(), a.getSummary(),
                    a.getCreatedAt());
        }).toList();
    }
}
