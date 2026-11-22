package com.splitmates.balance;

import com.splitmates.balance.BalanceDtos.*;
import com.splitmates.domain.*;
import com.splitmates.group.GroupAccess;
import com.splitmates.repo.ExpenseRepository;
import com.splitmates.repo.GroupMemberRepository;
import com.splitmates.repo.GroupRepository;
import com.splitmates.repo.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BR-04 net balances: Net = paid - owed + settlements sent - settlements received.
 * Only CONFIRMED settlements move balances; pending ones are surfaced separately (FR-45).
 * Computed with SQL aggregates, so a 5,000-expense group is a handful of indexed queries (NFR-02).
 */
@Service
public class BalanceService {
    private final ExpenseRepository expenses;
    private final SettlementRepository settlements;
    private final GroupMemberRepository members;
    private final GroupRepository groups;
    private final GroupAccess access;

    public BalanceService(ExpenseRepository expenses, SettlementRepository settlements, GroupMemberRepository members,
                          GroupRepository groups, GroupAccess access) {
        this.expenses = expenses;
        this.settlements = settlements;
        this.members = members;
        this.groups = groups;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> netBalances(Long groupId) {
        Map<Long, MemberBalance> detail = detailed(groupId);
        Map<Long, Long> out = new LinkedHashMap<>();
        detail.forEach((k, v) -> out.put(k, v.net()));
        return out;
    }

    private Map<Long, MemberBalance> detailed(Long groupId) {
        Map<Long, long[]> acc = new LinkedHashMap<>(); // paid, owed, sent, received
        for (GroupMember m : members.findByGroupIdOrderByIdAsc(groupId)) acc.put(m.getId(), new long[4]);
        for (Object[] r : expenses.sumPaidByMember(groupId)) acc.computeIfAbsent((Long) r[0], k -> new long[4])[0] += ((Number) r[1]).longValue();
        for (Object[] r : expenses.sumOwedByMember(groupId)) acc.computeIfAbsent((Long) r[0], k -> new long[4])[1] += ((Number) r[1]).longValue();
        for (Settlement s : settlements.findByGroupIdAndStatus(groupId, SettlementStatus.CONFIRMED)) {
            acc.computeIfAbsent(s.getFromMember(), k -> new long[4])[2] += s.getAmount();
            acc.computeIfAbsent(s.getToMember(), k -> new long[4])[3] += s.getAmount();
        }
        Map<Long, MemberBalance> out = new LinkedHashMap<>();
        acc.forEach((id, a) -> out.put(id, new MemberBalance(id, a[0], a[1], a[2], a[3], a[0] - a[1] + a[2] - a[3])));
        return out;
    }

    @Transactional(readOnly = true)
    public GroupBalances groupBalances(Long groupId, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        Map<Long, MemberBalance> detail = detailed(groupId);
        Map<Long, Long> nets = new LinkedHashMap<>();
        detail.forEach((k, v) -> nets.put(k, v.net()));
        List<SettlementDto> pending = settlements.findByGroupIdAndStatus(groupId, SettlementStatus.PENDING).stream()
                .map(SettlementService::toDto).toList();
        List<TransferDto> plan = DebtSimplifier.simplify(nets).stream().map(t -> new TransferDto(t.from(), t.to(), t.amount(),
                pending.stream().filter(p -> p.fromMemberId().equals(t.from()) && p.toMemberId().equals(t.to()))
                        .map(SettlementDto::id).findFirst().orElse(null))).toList();
        return new GroupBalances(groupId, me.getId(), nets.getOrDefault(me.getId(), 0L), new ArrayList<>(detail.values()), plan, pending);
    }

    /** FR-46: cross-group dashboard with per-friend balances derived from each group's settle-up plan. */
    @Transactional(readOnly = true)
    public MeSummary summaryForUser(Long userId) {
        List<GroupMember> mine = members.findByUserId(userId).stream().filter(GroupMember::isActive).toList();
        Map<Long, ExpenseGroup> gs = groups.findAllById(mine.stream().map(GroupMember::getGroupId).toList()).stream()
                .collect(Collectors.toMap(ExpenseGroup::getId, Function.identity()));
        Map<Long, GroupMember> allMembers = members.findByGroupIdInOrderByIdAsc(gs.keySet()).stream()
                .collect(Collectors.toMap(GroupMember::getId, Function.identity()));

        long owe = 0, get = 0;
        Map<String, FriendAcc> friends = new LinkedHashMap<>();
        List<GroupBrief> briefs = new ArrayList<>();
        for (GroupMember me : mine) {
            ExpenseGroup g = gs.get(me.getGroupId());
            if (g == null) continue;
            Map<Long, Long> nets = netBalances(g.getId());
            long myNet = nets.getOrDefault(me.getId(), 0L);
            if (myNet < 0) owe += -myNet;
            else get += myNet;
            briefs.add(new GroupBrief(g.getId(), g.getName(), g.getCoverEmoji(), g.getCoverColor(), g.getStatus(), myNet));
            for (DebtSimplifier.Transfer t : DebtSimplifier.simplify(nets)) {
                Long other;
                long signed;
                if (t.to().equals(me.getId())) {
                    other = t.from();
                    signed = t.amount();
                } else if (t.from().equals(me.getId())) {
                    other = t.to();
                    signed = -t.amount();
                } else continue;
                GroupMember o = allMembers.get(other);
                if (o == null) continue;
                String key = o.getUserId() != null ? "u" + o.getUserId() : "m" + o.getId();
                FriendAcc f = friends.computeIfAbsent(key, k -> new FriendAcc(k, o));
                f.amount += signed;
                f.groups.add(new FriendGroupAmount(g.getId(), g.getName(), signed));
            }
        }
        List<FriendBalance> fl = friends.values().stream()
                .map(f -> new FriendBalance(f.key, f.member.getUserId(), f.member.getDisplayName(), f.member.getAvatarEmoji(),
                        f.member.getAvatarColor(), f.amount, f.groups))
                .sorted(Comparator.comparingLong((FriendBalance f) -> -Math.abs(f.amount())))
                .toList();
        return new MeSummary(owe, get, get - owe, fl, briefs);
    }

    private static final class FriendAcc {
        final String key;
        final GroupMember member;
        long amount;
        final List<FriendGroupAmount> groups = new ArrayList<>();

        FriendAcc(String key, GroupMember member) {
            this.key = key;
            this.member = member;
        }
    }
}
