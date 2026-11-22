package com.splitmates.user;

import com.splitmates.common.ApiException;
import com.splitmates.domain.GroupMember;
import com.splitmates.domain.MemberRole;
import com.splitmates.domain.User;
import com.splitmates.repo.*;
import com.splitmates.user.UserDtos.UpdateProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserService {
    private final UserRepository users;
    private final GroupMemberRepository members;
    private final RefreshTokenRepository refreshTokens;
    private final ExpenseRepository expenses;
    private final SettlementRepository settlements;
    private final GroupRepository groups;

    public UserService(UserRepository users, GroupMemberRepository members, RefreshTokenRepository refreshTokens,
                       ExpenseRepository expenses, SettlementRepository settlements, GroupRepository groups) {
        this.users = users;
        this.members = members;
        this.refreshTokens = refreshTokens;
        this.expenses = expenses;
        this.settlements = settlements;
        this.groups = groups;
    }

    public User get(Long id) {
        return users.findById(id).filter(u -> !u.isDeleted()).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public User update(Long id, UpdateProfileRequest req) {
        User u = get(id);
        if (req.displayName() != null) u.setDisplayName(req.displayName().trim());
        if (req.avatarEmoji() != null) u.setAvatarEmoji(req.avatarEmoji());
        if (req.avatarColor() != null) u.setAvatarColor(req.avatarColor());
        if (req.upiId() != null) u.setUpiId(req.upiId().isBlank() ? null : req.upiId().trim());
        if (req.theme() != null) u.setTheme(req.theme());
        if (req.notifyInApp() != null) u.setNotifyInApp(req.notifyInApp());
        if (req.notifyWeekly() != null) u.setNotifyWeekly(req.notifyWeekly());
        if (req.showPhone() != null) u.setShowPhone(req.showPhone());

        // Keep group member cards in sync with the profile.
        for (GroupMember m : members.findByUserId(id)) {
            m.setDisplayName(u.getDisplayName());
            m.setAvatarEmoji(u.getAvatarEmoji());
            m.setAvatarColor(u.getAvatarColor());
        }
        return u;
    }

    /**
     * FR-04 / NFR-09: delete account. Shared history stays intact; the member rows are
     * detached from the user and shown as "Former member".
     */
    @Transactional
    public void delete(Long id) {
        User u = get(id);
        for (GroupMember m : members.findByUserId(id)) {
            if (m.isAdmin()) {
                members.findByGroupIdOrderByIdAsc(m.getGroupId()).stream()
                        .filter(o -> !o.getId().equals(m.getId()) && o.isActive() && o.getUserId() != null)
                        .findFirst()
                        .ifPresent(o -> o.setRole(MemberRole.ADMIN));
            }
            m.setUserId(null);
            m.setDisplayName("Former member");
            m.setAvatarEmoji("👤");
            m.setAvatarColor("#94A3B8");
            m.setRole(MemberRole.MEMBER);
        }
        refreshTokens.revokeAllForUser(id);
        u.setDeleted(true);
        u.setPhone(null);
        u.setEmail(null);
        u.setUpiId(null);
        u.setDisplayName("Former member");
    }

    /** NFR-09: personal data export. */
    @Transactional(readOnly = true)
    public Map<String, Object> export(Long id) {
        User u = get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", UserDtos.me(u));
        List<Map<String, Object>> gs = new ArrayList<>();
        for (GroupMember m : members.findByUserId(id)) {
            groups.findById(m.getGroupId()).ifPresent(g -> {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("group", g.getName());
                gm.put("role", m.getRole());
                gm.put("joinedAt", m.getJoinedAt());
                gm.put("expenses", expenses.findByGroupIdAndDeletedFalseOrderByExpenseDateDescIdDesc(g.getId()).stream()
                        .filter(e -> e.getPayers().stream().anyMatch(p -> p.getMemberId().equals(m.getId()))
                                || e.getShares().stream().anyMatch(s -> s.getMemberId().equals(m.getId())))
                        .map(e -> Map.of("date", e.getExpenseDate().toString(), "purpose", e.getPurpose(),
                                "amountPaise", e.getAmount()))
                        .toList());
                gm.put("settlements", settlements.findByGroupIdOrderByCreatedAtDesc(g.getId()).stream()
                        .filter(s -> s.getFromMember().equals(m.getId()) || s.getToMember().equals(m.getId()))
                        .map(s -> Map.of("date", s.getSettledOn().toString(), "amountPaise", s.getAmount(),
                                "status", s.getStatus().name()))
                        .toList());
                gs.add(gm);
            });
        }
        out.put("groups", gs);
        return out;
    }
}
