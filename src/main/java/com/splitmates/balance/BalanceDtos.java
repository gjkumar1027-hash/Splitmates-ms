package com.splitmates.balance;

import com.splitmates.domain.GroupStatus;
import com.splitmates.domain.SettlementMethod;
import com.splitmates.domain.SettlementStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BalanceDtos {
    private BalanceDtos() {
    }

    public record MemberBalance(Long memberId, long paid, long owed, long settledSent, long settledReceived, long net) {
    }

    public record TransferDto(Long fromMemberId, Long toMemberId, long amount, Long pendingSettlementId) {
    }

    public record GroupBalances(Long groupId, Long myMemberId, long myNet, List<MemberBalance> members,
                                List<TransferDto> plan, List<SettlementDto> pending) {
    }

    public record SettlementDto(Long id, Long groupId, Long fromMemberId, Long toMemberId, long amount,
                                SettlementMethod method, LocalDate settledOn, SettlementStatus status, String note,
                                Long createdByMemberId, Instant createdAt, Instant confirmedAt) {
    }

    public record SettlementRequest(@NotNull Long fromMemberId, @NotNull Long toMemberId,
                                    @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                    @NotNull SettlementMethod method, LocalDate settledOn, @Size(max = 200) String note) {
    }

    public record NudgeRequest(@NotNull Long toMemberId) {
    }

    public record NudgeResponse(String message) {
    }

    public record FriendGroupAmount(Long groupId, String groupName, long amount) {
    }

    /** amount > 0: friend owes me; amount < 0: I owe friend. */
    public record FriendBalance(String key, Long userId, String displayName, String avatarEmoji, String avatarColor,
                                long amount, List<FriendGroupAmount> groups) {
    }

    public record GroupBrief(Long groupId, String name, String coverEmoji, String coverColor, GroupStatus status, long myNet) {
    }

    public record MeSummary(long totalOwe, long totalGetBack, long net, List<FriendBalance> friends, List<GroupBrief> groups) {
    }
}
