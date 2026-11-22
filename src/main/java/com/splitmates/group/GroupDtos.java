package com.splitmates.group;

import com.splitmates.domain.GroupStatus;
import com.splitmates.domain.GroupType;
import com.splitmates.domain.MemberRole;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class GroupDtos {
    private GroupDtos() {
    }

    public record MemberDto(Long id, Long userId, String displayName, String avatarEmoji, String avatarColor,
                            MemberRole role, boolean active, boolean guest, String upiId, String phone) {
    }

    public record CreateGroupRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull GroupType type,
            @Size(max = 16) String coverEmoji,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String coverColor,
            LocalDate startDate,
            LocalDate endDate,
            @Size(max = 49) List<@NotBlank @Size(max = 60) String> guestNames) {
    }

    public record UpdateGroupRequest(
            @Size(min = 1, max = 60) String name,
            GroupType type,
            @Size(max = 16) String coverEmoji,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String coverColor,
            LocalDate startDate,
            LocalDate endDate) {
    }

    public record GroupDto(Long id, String name, GroupType type, String coverEmoji, String coverColor,
                           LocalDate startDate, LocalDate endDate, String currency, GroupStatus status,
                           String inviteCode, Instant createdAt, List<MemberDto> members, Long myMemberId,
                           MemberRole myRole, long myBalance, long totalSpend, long expenseCount) {
    }

    public record AddMemberRequest(@NotBlank @Size(max = 60) String displayName) {
    }

    public record RoleRequest(@NotNull MemberRole role) {
    }

    public record InvitePreview(String code, String groupName, String coverEmoji, String coverColor, GroupType type,
                                int memberCount, List<GuestRef> claimableGuests) {
    }

    public record GuestRef(Long memberId, String displayName) {
    }

    public record JoinRequest(@NotBlank String code, Long claimMemberId) {
    }
}
