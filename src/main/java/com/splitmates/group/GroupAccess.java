package com.splitmates.group;

import com.splitmates.common.ApiException;
import com.splitmates.domain.ExpenseGroup;
import com.splitmates.domain.GroupMember;
import com.splitmates.repo.GroupMemberRepository;
import com.splitmates.repo.GroupRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Central authorisation checks (NFR-07): every group-scoped call goes through here. */
@Component
public class GroupAccess {
    private final GroupRepository groups;
    private final GroupMemberRepository members;

    public GroupAccess(GroupRepository groups, GroupMemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    public ExpenseGroup group(Long groupId) {
        return groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Group"));
    }

    /** Returns the caller's membership or throws 403 (AC-06). */
    public GroupMember requireMember(Long groupId, Long userId) {
        return members.findByGroupIdAndUserIdAndActiveTrue(groupId, userId)
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this group"));
    }

    public GroupMember requireAdmin(Long groupId, Long userId) {
        GroupMember m = requireMember(groupId, userId);
        if (!m.isAdmin()) throw ApiException.forbidden("Only a group admin can do this");
        return m;
    }

    /** BR-08: archived groups are read-only for expenses. */
    public void requireNotArchived(ExpenseGroup g) {
        if (g.isArchived()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_ARCHIVED", "This group is archived and read-only");
        }
    }

    public GroupMember memberOf(Long groupId, Long memberId) {
        GroupMember m = members.findById(memberId).orElseThrow(() -> ApiException.badRequest("Unknown member " + memberId));
        if (!m.getGroupId().equals(groupId)) throw ApiException.badRequest("Member " + memberId + " is not in this group");
        return m;
    }
}
