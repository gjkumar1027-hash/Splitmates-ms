package com.splitmates.balance;

import com.splitmates.activity.ActivityService;
import com.splitmates.balance.BalanceDtos.*;
import com.splitmates.common.ApiException;
import com.splitmates.common.Money;
import com.splitmates.domain.*;
import com.splitmates.group.GroupAccess;
import com.splitmates.notification.NotificationService;
import com.splitmates.repo.NudgeRepository;
import com.splitmates.repo.SettlementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class SettlementService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SettlementRepository repo;
    private final NudgeRepository nudges;
    private final GroupAccess access;
    private final BalanceService balances;
    private final ActivityService activity;
    private final NotificationService notifications;

    public SettlementService(SettlementRepository repo, NudgeRepository nudges, GroupAccess access, BalanceService balances,
                             ActivityService activity, NotificationService notifications) {
        this.repo = repo;
        this.nudges = nudges;
        this.access = access;
        this.balances = balances;
        this.activity = activity;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<SettlementDto> list(Long groupId, Long userId) {
        access.requireMember(groupId, userId);
        return repo.findByGroupIdOrderByCreatedAtDesc(groupId).stream().map(SettlementService::toDto).toList();
    }

    /**
     * FR-43: record a settlement. Allowed in archived groups (BR-08). It needs the receiver's
     * confirmation (FR-45) unless the receiver recorded it themselves or is a guest without an account.
     */
    @Transactional
    public SettlementDto create(Long groupId, SettlementRequest req, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        GroupMember from = access.memberOf(groupId, req.fromMemberId());
        GroupMember to = access.memberOf(groupId, req.toMemberId());
        if (from.getId().equals(to.getId())) throw ApiException.badRequest("Payer and receiver must be different");
        boolean involved = me.getId().equals(from.getId()) || me.getId().equals(to.getId());
        if (!involved && !me.isAdmin()) throw ApiException.forbidden("You can only record settlements you are part of");

        Settlement s = new Settlement();
        s.setGroupId(groupId);
        s.setFromMember(from.getId());
        s.setToMember(to.getId());
        s.setAmount(Money.toPaise(req.amount()));
        s.setMethod(req.method());
        s.setSettledOn(req.settledOn() == null ? LocalDate.now(IST) : req.settledOn());
        s.setNote(req.note());
        s.setCreatedByMember(me.getId());
        boolean autoConfirm = to.getId().equals(me.getId()) || to.isGuest();
        s.setStatus(autoConfirm ? SettlementStatus.CONFIRMED : SettlementStatus.PENDING);
        if (autoConfirm) s.setConfirmedAt(Instant.now());
        s = repo.save(s);

        activity.log(groupId, me.getId(), "SETTLEMENT_RECORDED", "SETTLEMENT", s.getId(),
                from.getDisplayName() + " paid " + to.getDisplayName() + " " + Money.format(s.getAmount()) + " via " + s.getMethod().name()
                        + (autoConfirm ? "" : " (pending confirmation)"), null, toDto(s));
        if (!autoConfirm) {
            notifications.notifyMember(to, groupId, "SETTLEMENT_PENDING",
                    from.getDisplayName() + " says they paid you " + Money.format(s.getAmount()) + " in " + g.getName() + " — please confirm",
                    "/groups/" + groupId + "?tab=balances");
        } else if (!from.getId().equals(me.getId())) {
            notifications.notifyMember(from, groupId, "SETTLEMENT_CONFIRMED",
                    to.getDisplayName() + " recorded your payment of " + Money.format(s.getAmount()) + " in " + g.getName(),
                    "/groups/" + groupId + "?tab=balances");
        }
        return toDto(s);
    }

    @Transactional
    public SettlementDto confirm(Long settlementId, Long userId, boolean accept) {
        Settlement s = repo.findById(settlementId).orElseThrow(() -> ApiException.notFound("Settlement"));
        GroupMember me = access.requireMember(s.getGroupId(), userId);
        GroupMember to = access.memberOf(s.getGroupId(), s.getToMember());
        GroupMember from = access.memberOf(s.getGroupId(), s.getFromMember());
        boolean canConfirm = me.getId().equals(to.getId()) || (to.isGuest() && me.isAdmin());
        if (!canConfirm) throw ApiException.forbidden("Only the receiver can confirm this payment");
        if (s.getStatus() != SettlementStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PENDING", "This settlement is already " + s.getStatus().name().toLowerCase());
        }
        s.setStatus(accept ? SettlementStatus.CONFIRMED : SettlementStatus.REJECTED);
        s.setConfirmedAt(Instant.now());
        activity.log(s.getGroupId(), me.getId(), accept ? "SETTLEMENT_CONFIRMED" : "SETTLEMENT_REJECTED", "SETTLEMENT", s.getId(),
                me.getDisplayName() + (accept ? " confirmed " : " rejected ") + from.getDisplayName() + "'s payment of " + Money.format(s.getAmount()),
                null, toDto(s));
        notifications.notifyMember(from, s.getGroupId(), accept ? "SETTLEMENT_CONFIRMED" : "SETTLEMENT_REJECTED",
                to.getDisplayName() + (accept ? " confirmed" : " did not confirm") + " your payment of " + Money.format(s.getAmount()),
                "/groups/" + s.getGroupId() + "?tab=balances");
        return toDto(s);
    }

    /** FR-62: friendly nudge, max one per friend per 24 h. */
    @Transactional
    public NudgeResponse nudge(Long groupId, NudgeRequest req, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        GroupMember target = access.memberOf(groupId, req.toMemberId());
        if (target.getId().equals(me.getId())) throw ApiException.badRequest("You can't nudge yourself");
        if (target.isGuest()) throw ApiException.badRequest(target.getDisplayName() + " isn't on SplitMates yet — share the invite link instead");
        var bal = balances.groupBalances(groupId, userId);
        long owes = bal.plan().stream().filter(t -> t.fromMemberId().equals(target.getId()) && t.toMemberId().equals(me.getId()))
                .mapToLong(TransferDto::amount).sum();
        if (owes <= 0) throw ApiException.badRequest(target.getDisplayName() + " doesn't owe you anything right now");
        if (nudges.existsByFromMemberAndToMemberAndCreatedAtAfter(me.getId(), target.getId(), Instant.now().minus(Duration.ofHours(24)))) {
            throw ApiException.tooMany("You already nudged " + target.getDisplayName() + " in the last 24 hours");
        }
        Nudge n = new Nudge();
        n.setGroupId(groupId);
        n.setFromMember(me.getId());
        n.setToMember(target.getId());
        nudges.save(n);
        String msg = "Hey " + target.getDisplayName() + ", " + me.getDisplayName() + "'s wallet misses you " + Money.format(owes) + " 💸 (" + g.getName() + ")";
        notifications.notifyMember(target, groupId, "NUDGE", msg, "/groups/" + groupId + "?tab=balances");
        activity.log(groupId, me.getId(), "NUDGE_SENT", "MEMBER", target.getId(), me.getDisplayName() + " nudged " + target.getDisplayName(), null, null);
        return new NudgeResponse(msg);
    }

    static SettlementDto toDto(Settlement s) {
        return new SettlementDto(s.getId(), s.getGroupId(), s.getFromMember(), s.getToMember(), s.getAmount(), s.getMethod(),
                s.getSettledOn(), s.getStatus(), s.getNote(), s.getCreatedByMember(), s.getCreatedAt(), s.getConfirmedAt());
    }
}
