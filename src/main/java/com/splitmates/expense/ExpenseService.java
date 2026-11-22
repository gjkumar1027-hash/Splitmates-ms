package com.splitmates.expense;

import com.splitmates.activity.ActivityService;
import com.splitmates.common.ApiException;
import com.splitmates.common.Money;
import com.splitmates.domain.*;
import com.splitmates.expense.ExpenseDtos.*;
import com.splitmates.group.GroupAccess;
import com.splitmates.notification.NotificationService;
import com.splitmates.repo.ExpenseRepository;
import com.splitmates.repo.GroupMemberRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExpenseService {
    private static final Duration RESTORE_WINDOW = Duration.ofDays(30); // BR-06

    private final ExpenseRepository expenses;
    private final GroupMemberRepository members;
    private final GroupAccess access;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final ReceiptStorage receipts;

    public ExpenseService(ExpenseRepository expenses, GroupMemberRepository members, GroupAccess access,
                          ActivityService activity, NotificationService notifications, ReceiptStorage receipts) {
        this.expenses = expenses;
        this.members = members;
        this.access = access;
        this.activity = activity;
        this.notifications = notifications;
        this.receipts = receipts;
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public ExpensePage list(Long groupId, Long userId, ExpenseFilter f, int page, int size) {
        GroupMember me = access.requireMember(groupId, userId);
        Specification<Expense> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("groupId"), groupId));
            ps.add(cb.isFalse(root.get("deleted")));
            if (f.q() != null && !f.q().isBlank()) {
                ps.add(cb.like(cb.lower(root.get("purpose")), "%" + f.q().toLowerCase().trim().replace("%", "\\%") + "%"));
            }
            if (f.from() != null) ps.add(cb.greaterThanOrEqualTo(root.get("expenseDate"), f.from()));
            if (f.to() != null) ps.add(cb.lessThanOrEqualTo(root.get("expenseDate"), f.to()));
            if (f.category() != null) ps.add(cb.equal(root.get("category"), f.category()));
            if (f.payerId() != null) {
                Subquery<Long> sq = query.subquery(Long.class);
                Root<ExpensePayer> p = sq.from(ExpensePayer.class);
                sq.select(p.get("id")).where(cb.equal(p.get("expense"), root), cb.equal(p.get("memberId"), f.payerId()));
                ps.add(cb.exists(sq));
            }
            if (f.participantId() != null) {
                Subquery<Long> sq = query.subquery(Long.class);
                Root<ExpenseShare> s = sq.from(ExpenseShare.class);
                sq.select(s.get("id")).where(cb.equal(s.get("expense"), root), cb.equal(s.get("memberId"), f.participantId()));
                ps.add(cb.exists(sq));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Expense> result = expenses.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Order.desc("expenseDate"), Sort.Order.desc("id"))));
        return new ExpensePage(result.getContent().stream().map(e -> toDto(e, me.getId())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ExpenseDto get(Long expenseId, Long userId) {
        Expense e = load(expenseId);
        GroupMember me = access.requireMember(e.getGroupId(), userId);
        return toDto(e, me.getId());
    }

    @Transactional(readOnly = true)
    public List<ExpenseDto> recentlyDeleted(Long groupId, Long userId) {
        GroupMember me = access.requireAdmin(groupId, userId);
        return expenses.findByGroupIdAndDeletedTrueAndDeletedAtAfterOrderByDeletedAtDesc(groupId, Instant.now().minus(RESTORE_WINDOW))
                .stream().map(e -> toDto(e, me.getId())).toList();
    }

    // ---------- commands ----------

    @Transactional
    public ExpenseDto create(Long groupId, ExpenseRequest req, Long userId) {
        GroupMember me = access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        access.requireNotArchived(g);

        Expense e = new Expense();
        e.setGroupId(groupId);
        e.setCreatedByMember(me.getId());
        e.setUpdatedByMember(me.getId());
        apply(e, req, Set.of());
        e = expenses.save(e);

        activity.log(groupId, me.getId(), "EXPENSE_ADDED", "EXPENSE", e.getId(),
                me.getDisplayName() + " added \"" + e.getPurpose() + "\" (" + Money.format(e.getAmount()) + ")", null, snapshot(e));
        notifyInvolved(e, me, g, "added");
        return toDto(e, me.getId());
    }

    @Transactional
    public ExpenseDto update(Long expenseId, ExpenseRequest req, Long userId) {
        Expense e = load(expenseId);
        if (e.isDeleted()) throw ApiException.notFound("Expense");
        GroupMember me = requireEditor(e, userId);
        ExpenseGroup g = access.group(e.getGroupId());
        access.requireNotArchived(g);

        // BR-07 optimistic locking with a friendly message.
        if (req.version() == null || req.version() != e.getVersion()) {
            String who = members.findById(e.getUpdatedByMember() == null ? -1L : e.getUpdatedByMember())
                    .map(GroupMember::getDisplayName).orElse("someone");
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "This expense was changed by " + who + " — review the latest version");
        }
        Map<String, Object> before = snapshot(e);
        Set<Long> existingMembers = new HashSet<>();
        e.getPayers().forEach(p -> existingMembers.add(p.getMemberId()));
        e.getShares().forEach(s -> existingMembers.add(s.getMemberId()));

        apply(e, req, existingMembers);
        e.setUpdatedByMember(me.getId());
        e.setUpdatedAt(Instant.now());
        expenses.saveAndFlush(e);

        activity.log(e.getGroupId(), me.getId(), "EXPENSE_EDITED", "EXPENSE", e.getId(),
                me.getDisplayName() + " edited \"" + e.getPurpose() + "\" (" + Money.format(e.getAmount()) + ")", before, snapshot(e));
        notifyInvolved(e, me, g, "updated");
        return toDto(e, me.getId());
    }

    @Transactional
    public void delete(Long expenseId, Long userId) {
        Expense e = load(expenseId);
        if (e.isDeleted()) return;
        GroupMember me = requireEditor(e, userId);
        access.requireNotArchived(access.group(e.getGroupId()));
        e.setDeleted(true);
        e.setDeletedAt(Instant.now());
        e.setUpdatedByMember(me.getId());
        activity.log(e.getGroupId(), me.getId(), "EXPENSE_DELETED", "EXPENSE", e.getId(),
                me.getDisplayName() + " deleted \"" + e.getPurpose() + "\" (" + Money.format(e.getAmount()) + ")", snapshot(e), null);
    }

    @Transactional
    public ExpenseDto restore(Long expenseId, Long userId) {
        Expense e = load(expenseId);
        GroupMember me = access.requireAdmin(e.getGroupId(), userId);
        access.requireNotArchived(access.group(e.getGroupId()));
        if (!e.isDeleted()) return toDto(e, me.getId());
        if (e.getDeletedAt() != null && e.getDeletedAt().isBefore(Instant.now().minus(RESTORE_WINDOW))) {
            throw ApiException.badRequest("Expenses can only be restored within 30 days of deletion");
        }
        e.setDeleted(false);
        e.setDeletedAt(null);
        e.setUpdatedByMember(me.getId());
        activity.log(e.getGroupId(), me.getId(), "EXPENSE_RESTORED", "EXPENSE", e.getId(),
                me.getDisplayName() + " restored \"" + e.getPurpose() + "\"", null, snapshot(e));
        return toDto(e, me.getId());
    }

    @Transactional
    public ExpenseDto attachReceipt(Long expenseId, MultipartFile file, Long userId) {
        Expense e = load(expenseId);
        GroupMember me = requireEditor(e, userId);
        access.requireNotArchived(access.group(e.getGroupId()));
        String old = e.getReceiptPath();
        e.setReceiptPath(receipts.store(e.getGroupId(), file));
        e.setUpdatedByMember(me.getId());
        receipts.delete(old);
        activity.log(e.getGroupId(), me.getId(), "RECEIPT_ADDED", "EXPENSE", e.getId(),
                me.getDisplayName() + " attached a receipt to \"" + e.getPurpose() + "\"", null, null);
        return toDto(e, me.getId());
    }

    @Transactional(readOnly = true)
    public java.nio.file.Path receipt(Long expenseId, Long userId) {
        Expense e = load(expenseId);
        access.requireMember(e.getGroupId(), userId);
        if (e.getReceiptPath() == null) throw ApiException.notFound("Receipt");
        return receipts.resolve(e.getReceiptPath());
    }

    // ---------- helpers ----------

    private Expense load(Long id) {
        return expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense"));
    }

    /** FR-25: only the creator or a group admin may edit/delete. */
    private GroupMember requireEditor(Expense e, Long userId) {
        GroupMember me = access.requireMember(e.getGroupId(), userId);
        if (!me.isAdmin() && !me.getId().equals(e.getCreatedByMember())) {
            throw ApiException.forbidden("Only the person who added this expense or an admin can change it");
        }
        return me;
    }

    private void apply(Expense e, ExpenseRequest req, Set<Long> alreadyInExpense) {
        long total = Money.toPaise(req.amount());
        Map<Long, GroupMember> groupMembers = members.findByGroupIdOrderByIdAsc(e.getGroupId()).stream()
                .collect(Collectors.toMap(GroupMember::getId, Function.identity()));
        java.util.function.Consumer<Long> check = id -> {
            GroupMember m = groupMembers.get(id);
            if (m == null) throw ApiException.badRequest("Member " + id + " is not in this group");
            if (!m.isActive() && !alreadyInExpense.contains(id)) throw ApiException.badRequest(m.getDisplayName() + " is no longer in this group");
        };

        // Payers (FR-20, FR-24, BR-03)
        LinkedHashMap<Long, Long> paid = new LinkedHashMap<>();
        if (req.payers().size() == 1 && req.payers().getFirst().amount() == null) {
            paid.put(req.payers().getFirst().memberId(), total);
        } else {
            for (PayerInput p : req.payers()) {
                if (p.amount() == null) throw ApiException.badRequest("Enter how much each payer paid");
                if (paid.put(p.memberId(), Money.toPaise(p.amount())) != null) throw ApiException.badRequest("A payer appears twice");
            }
            paid.values().removeIf(v -> v == 0);
            long sum = paid.values().stream().mapToLong(Long::longValue).sum();
            if (sum != total) {
                throw ApiException.badRequest("Payers add up to " + Money.format(sum) + " but the total is " + Money.format(total));
            }
            if (paid.isEmpty()) throw ApiException.badRequest("Choose who paid");
        }
        paid.keySet().forEach(check);

        // Shares (FR-30..33, BR-02, BR-03)
        List<SplitCalculator.Line> lines = req.shares().stream()
                .map(s -> new SplitCalculator.Line(s.memberId(), s.value())).toList();
        lines.forEach(l -> check.accept(l.memberId()));
        LinkedHashMap<Long, Long> owed = SplitCalculator.split(total, req.splitType(), lines, new ArrayList<>(paid.keySet()));
        Map<Long, BigDecimal> rawValues = new HashMap<>();
        lines.forEach(l -> rawValues.put(l.memberId(), l.value()));

        e.setExpenseDate(req.expenseDate());
        e.setPurpose(req.purpose().trim());
        e.setAmount(total);
        e.setCategory(req.category());
        e.setSplitType(req.splitType());
        e.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());

        e.getPayers().clear();
        paid.forEach((m, amt) -> e.getPayers().add(new ExpensePayer(e, m, amt)));
        e.getShares().clear();
        owed.forEach((m, amt) -> {
            if (amt > 0 || req.splitType() == SplitType.EQUAL) {
                e.getShares().add(new ExpenseShare(e, m, req.splitType() == SplitType.EQUAL ? null : rawValues.get(m), amt));
            }
        });
    }

    private void notifyInvolved(Expense e, GroupMember actor, ExpenseGroup g, String verb) {
        Map<Long, Long> shares = new HashMap<>();
        e.getShares().forEach(s -> shares.put(s.getMemberId(), s.getAmountOwed()));
        Set<Long> involved = new LinkedHashSet<>(shares.keySet());
        e.getPayers().forEach(p -> involved.add(p.getMemberId()));
        for (GroupMember m : members.findAllById(involved)) {
            if (m.getId().equals(actor.getId())) continue;
            long share = shares.getOrDefault(m.getId(), 0L);
            notifications.notifyMember(m, g.getId(), "EXPENSE_" + verb.toUpperCase(),
                    actor.getDisplayName() + " " + verb + " \"" + e.getPurpose() + "\" in " + g.getName()
                            + (share > 0 ? " — your share " + Money.format(share) : ""),
                    "/groups/" + g.getId());
        }
    }

    private Map<String, Object> snapshot(Expense e) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("expenseDate", e.getExpenseDate().toString());
        s.put("purpose", e.getPurpose());
        s.put("amount", e.getAmount());
        s.put("category", e.getCategory().name());
        s.put("splitType", e.getSplitType().name());
        s.put("notes", e.getNotes());
        s.put("payers", e.getPayers().stream().map(p -> Map.of("memberId", p.getMemberId(), "amount", p.getAmountPaid())).toList());
        s.put("shares", e.getShares().stream().map(x -> Map.of("memberId", x.getMemberId(), "amount", x.getAmountOwed())).toList());
        return s;
    }

    static ExpenseDto toDto(Expense e, Long myMemberId) {
        long myShare = e.getShares().stream().filter(s -> s.getMemberId().equals(myMemberId)).mapToLong(ExpenseShare::getAmountOwed).sum();
        long myPaid = e.getPayers().stream().filter(p -> p.getMemberId().equals(myMemberId)).mapToLong(ExpensePayer::getAmountPaid).sum();
        return new ExpenseDto(e.getId(), e.getGroupId(), e.getExpenseDate(), e.getPurpose(), e.getAmount(), e.getCategory(),
                e.getSplitType(), e.getNotes(), e.getReceiptPath() != null,
                e.getPayers().stream().map(p -> new PayerDto(p.getMemberId(), p.getAmountPaid())).toList(),
                e.getShares().stream().map(s -> new ShareDto(s.getMemberId(), s.getShareValue(), s.getAmountOwed())).toList(),
                e.getCreatedByMember(), e.getUpdatedByMember(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion(),
                e.isDeleted(), e.getDeletedAt(), myShare, myPaid);
    }
}
