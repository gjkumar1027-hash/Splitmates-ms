package com.splitmates.group;

import com.splitmates.activity.ActivityService;
import com.splitmates.balance.BalanceService;
import com.splitmates.common.ApiException;
import com.splitmates.common.Hashing;
import com.splitmates.domain.*;
import com.splitmates.group.GroupDtos.*;
import com.splitmates.notification.NotificationService;
import com.splitmates.repo.ExpenseRepository;
import com.splitmates.repo.GroupMemberRepository;
import com.splitmates.repo.GroupRepository;
import com.splitmates.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class GroupService {
    public static final int MAX_MEMBERS = 50; // FR-17
    private static final String[] MEMBER_COLORS = {"#7C3AED", "#EC4899", "#10B981", "#0EA5E9", "#F59E0B", "#F43F5E", "#8B5CF6", "#14B8A6", "#6366F1", "#84CC16"};
    private static final String[] GUEST_EMOJIS = {"🙂", "😄", "🤠", "🥳", "😺", "🐻", "🐶", "🐱"};

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final ExpenseRepository expenses;
    private final GroupAccess access;
    private final MemberMapper mapper;
    private final BalanceService balances;
    private final ActivityService activity;
    private final NotificationService notifications;

    public GroupService(GroupRepository groups, GroupMemberRepository members, UserRepository users,
                        ExpenseRepository expenses, GroupAccess access, MemberMapper mapper, BalanceService balances,
                        ActivityService activity, NotificationService notifications) {
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.expenses = expenses;
        this.access = access;
        this.mapper = mapper;
        this.balances = balances;
        this.activity = activity;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<GroupDto> list(Long userId) {
        return groups.findAllForUser(userId).stream().map(g -> toDto(g, userId)).toList();
    }

    @Transactional(readOnly = true)
    public GroupDto get(Long groupId, Long userId) {
        access.requireMember(groupId, userId);
        return toDto(access.group(groupId), userId);
    }

    @Transactional
    public GroupDto create(CreateGroupRequest req, Long userId) {
        User user = users.findById(userId).orElseThrow();
        validateDates(req.startDate(), req.endDate());
        ExpenseGroup g = new ExpenseGroup();
        g.setName(req.name().trim());
        g.setType(req.type());
        g.setCoverEmoji(req.coverEmoji() == null || req.coverEmoji().isBlank() ? defaultEmoji(req.type()) : req.coverEmoji());
        g.setCoverColor(req.coverColor() == null ? "#7C3AED" : req.coverColor());
        g.setStartDate(req.startDate());
        g.setEndDate(req.endDate());
        g.setCreatedBy(userId);
        g.setInviteCode(uniqueInviteCode());
        g = groups.save(g);

        GroupMember admin = new GroupMember();
        admin.setGroupId(g.getId());
        admin.setUserId(userId);
        admin.setDisplayName(user.getDisplayName());
        admin.setAvatarEmoji(user.getAvatarEmoji());
        admin.setAvatarColor(user.getAvatarColor());
        admin.setRole(MemberRole.ADMIN);
        admin = members.save(admin);

        activity.log(g.getId(), admin.getId(), "GROUP_CREATED", "GROUP", g.getId(),
                admin.getDisplayName() + " created the group \"" + g.getName() + "\"", null, null);

        if (req.guestNames() != null) {
            int i = 1;
            for (String name : req.guestNames()) {
                if (i >= MAX_MEMBERS) break;
                members.save(newGuest(g.getId(), name.trim(), i++));
            }
        }
        return toDto(g, userId);
    }

    @Transactional
    public GroupDto update(Long groupId, UpdateGroupRequest req, Long userId) {
        GroupMember me = access.requireAdmin(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        if (req.name() != null) g.setName(req.name().trim());
        if (req.type() != null) g.setType(req.type());
        if (req.coverEmoji() != null) g.setCoverEmoji(req.coverEmoji());
        if (req.coverColor() != null) g.setCoverColor(req.coverColor());
        if (req.startDate() != null) g.setStartDate(req.startDate());
        if (req.endDate() != null) g.setEndDate(req.endDate());
        validateDates(g.getStartDate(), g.getEndDate());
        activity.log(groupId, me.getId(), "GROUP_UPDATED", "GROUP", groupId, me.getDisplayName() + " updated group details", null, null);
        return toDto(g, userId);
    }

    @Transactional
    public GroupDto setArchived(Long groupId, boolean archived, Long userId) {
        GroupMember me = access.requireAdmin(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        g.setStatus(archived ? GroupStatus.ARCHIVED : GroupStatus.ACTIVE);
        g.setArchivedAt(archived ? Instant.now() : null);
        activity.log(groupId, me.getId(), archived ? "GROUP_ARCHIVED" : "GROUP_REOPENED", "GROUP", groupId,
                me.getDisplayName() + (archived ? " archived the group" : " reopened the group"), null, null);
        return toDto(g, userId);
    }

    @Transactional
    public MemberDto addGuest(Long groupId, AddMemberRequest req, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        access.requireNotArchived(g);
        ensureCapacity(groupId);
        int n = (int) members.countByGroupIdAndActiveTrue(groupId);
        GroupMember guest = members.save(newGuest(groupId, req.displayName().trim(), n));
        activity.log(groupId, me.getId(), "MEMBER_ADDED", "MEMBER", guest.getId(),
                me.getDisplayName() + " added " + guest.getDisplayName() + " as a guest", null, null);
        return mapper.toDto(guest, null);
    }

    /** FR-15: a member cannot be removed while their balance is non-zero. */
    @Transactional
    public void removeMember(Long groupId, Long memberId, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        GroupMember target = access.memberOf(groupId, memberId);
        boolean self = target.getId().equals(me.getId());
        if (!self && !me.isAdmin()) throw ApiException.forbidden("Only a group admin can remove members");
        if (!target.isActive()) return;
        long bal = balances.netBalances(groupId).getOrDefault(memberId, 0L);
        if (bal != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_NOT_ZERO",
                    target.getDisplayName() + " still has an unsettled balance and cannot be removed");
        }
        if (target.isAdmin()) {
            List<GroupMember> otherAdmins = members.findByGroupIdOrderByIdAsc(groupId).stream()
                    .filter(m -> m.isActive() && m.isAdmin() && !m.getId().equals(target.getId())).toList();
            if (otherAdmins.isEmpty()) {
                Optional<GroupMember> successor = members.findByGroupIdOrderByIdAsc(groupId).stream()
                        .filter(m -> m.isActive() && !m.isGuest() && !m.getId().equals(target.getId())).findFirst();
                successor.ifPresent(s -> s.setRole(MemberRole.ADMIN));
            }
        }
        target.setActive(false);
        target.setRole(MemberRole.MEMBER);
        activity.log(groupId, me.getId(), self ? "MEMBER_LEFT" : "MEMBER_REMOVED", "MEMBER", target.getId(),
                self ? me.getDisplayName() + " left the group" : me.getDisplayName() + " removed " + target.getDisplayName(), null, null);
    }

    @Transactional
    public MemberDto setRole(Long groupId, Long memberId, MemberRole role, Long userId) {
        GroupMember me = access.requireAdmin(groupId, userId);
        GroupMember target = access.memberOf(groupId, memberId);
        if (target.isGuest()) throw ApiException.badRequest("Guests cannot be admins");
        if (role == MemberRole.MEMBER && target.isAdmin()) {
            long admins = members.findByGroupIdOrderByIdAsc(groupId).stream().filter(m -> m.isActive() && m.isAdmin()).count();
            if (admins <= 1) throw ApiException.badRequest("A group needs at least one admin");
        }
        target.setRole(role);
        activity.log(groupId, me.getId(), "ROLE_CHANGED", "MEMBER", target.getId(),
                me.getDisplayName() + " made " + target.getDisplayName() + " " + (role == MemberRole.ADMIN ? "an admin" : "a member"), null, null);
        return mapper.toDtos(List.of(target)).getFirst();
    }

    @Transactional(readOnly = true)
    public InvitePreview preview(String code) {
        ExpenseGroup g = groups.findByInviteCode(code.toUpperCase()).orElseThrow(() -> ApiException.notFound("Invite"));
        List<GroupMember> list = members.findByGroupIdOrderByIdAsc(g.getId());
        List<GuestRef> guests = list.stream().filter(m -> m.isActive() && m.isGuest() && !"Former member".equals(m.getDisplayName()))
                .map(m -> new GuestRef(m.getId(), m.getDisplayName())).toList();
        int count = (int) list.stream().filter(GroupMember::isActive).count();
        return new InvitePreview(g.getInviteCode(), g.getName(), g.getCoverEmoji(), g.getCoverColor(), g.getType(), count, guests);
    }

    /** FR-11 join by link; FR-13 optionally claim an existing guest profile. */
    @Transactional
    public GroupDto join(JoinRequest req, Long userId) {
        ExpenseGroup g = groups.findByInviteCode(req.code().trim().toUpperCase()).orElseThrow(() -> ApiException.notFound("Invite"));
        User user = users.findById(userId).orElseThrow();
        Optional<GroupMember> existing = members.findByGroupIdAndUserId(g.getId(), userId);
        if (existing.isPresent() && existing.get().isActive()) return toDto(g, userId);
        if (g.isArchived()) throw new ApiException(HttpStatus.CONFLICT, "GROUP_ARCHIVED", "This group is archived");

        GroupMember member;
        if (existing.isPresent()) {
            ensureCapacity(g.getId());
            member = existing.get();
            member.setActive(true);
        } else if (req.claimMemberId() != null) {
            member = access.memberOf(g.getId(), req.claimMemberId());
            if (!member.isGuest() || !member.isActive()) throw ApiException.badRequest("That profile has already been claimed");
            member.setUserId(userId);
        } else {
            ensureCapacity(g.getId());
            member = new GroupMember();
            member.setGroupId(g.getId());
            member.setUserId(userId);
        }
        String previousName = member.getDisplayName();
        member.setDisplayName(user.getDisplayName());
        member.setAvatarEmoji(user.getAvatarEmoji());
        member.setAvatarColor(user.getAvatarColor());
        member = members.save(member);

        String summary = req.claimMemberId() != null
                ? user.getDisplayName() + " joined and claimed the guest profile \"" + previousName + "\""
                : user.getDisplayName() + " joined the group";
        activity.log(g.getId(), member.getId(), "MEMBER_JOINED", "MEMBER", member.getId(), summary, null, null);
        for (GroupMember m : members.findByGroupIdOrderByIdAsc(g.getId())) {
            if (m.isActive() && !m.getId().equals(member.getId())) {
                notifications.notifyMember(m, g.getId(), "MEMBER_JOINED", user.getDisplayName() + " joined " + g.getName(), "/groups/" + g.getId());
            }
        }
        return toDto(g, userId);
    }

    private GroupDto toDto(ExpenseGroup g, Long userId) {
        List<GroupMember> list = members.findByGroupIdOrderByIdAsc(g.getId());
        GroupMember me = list.stream().filter(m -> userId.equals(m.getUserId()) && m.isActive()).findFirst().orElse(null);
        Map<Long, Long> nets = balances.netBalances(g.getId());
        long myBalance = me == null ? 0 : nets.getOrDefault(me.getId(), 0L);
        return new GroupDto(g.getId(), g.getName(), g.getType(), g.getCoverEmoji(), g.getCoverColor(), g.getStartDate(),
                g.getEndDate(), g.getCurrency(), g.getStatus(), g.getInviteCode(), g.getCreatedAt(), mapper.toDtos(list),
                me == null ? null : me.getId(), me == null ? null : me.getRole(), myBalance,
                expenses.totalSpend(g.getId()), expenses.countByGroupIdAndDeletedFalse(g.getId()));
    }

    private GroupMember newGuest(Long groupId, String name, int index) {
        GroupMember guest = new GroupMember();
        guest.setGroupId(groupId);
        guest.setDisplayName(name);
        guest.setAvatarEmoji(GUEST_EMOJIS[index % GUEST_EMOJIS.length]);
        guest.setAvatarColor(MEMBER_COLORS[index % MEMBER_COLORS.length]);
        return guest;
    }

    private void ensureCapacity(Long groupId) {
        if (members.countByGroupIdAndActiveTrue(groupId) >= MAX_MEMBERS) {
            throw ApiException.badRequest("A group can have at most " + MAX_MEMBERS + " members");
        }
    }

    private void validateDates(java.time.LocalDate start, java.time.LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) throw ApiException.badRequest("End date must be after start date");
    }

    private String uniqueInviteCode() {
        for (int i = 0; i < 10; i++) {
            String c = Hashing.inviteCode();
            if (groups.findByInviteCode(c).isEmpty()) return c;
        }
        throw new IllegalStateException("Could not allocate invite code");
    }

    private static String defaultEmoji(GroupType t) {
        return switch (t) {
            case TRIP -> "✈️";
            case HOME -> "🏠";
            case PARTY -> "🎉";
            case FOOD -> "🍕";
            case OTHER -> "💸";
        };
    }
}
