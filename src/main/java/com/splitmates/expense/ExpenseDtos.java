package com.splitmates.expense;

import com.splitmates.domain.Category;
import com.splitmates.domain.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ExpenseDtos {
    private ExpenseDtos() {
    }

    public record PayerInput(@NotNull Long memberId, @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    public record ShareInput(@NotNull Long memberId, @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal value) {
    }

    public record ExpenseRequest(
            @NotNull LocalDate expenseDate,
            @NotBlank @Size(max = 100) String purpose,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than 0") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull Category category,
            @NotNull SplitType splitType,
            @Size(max = 500) String notes,
            @NotEmpty(message = "Choose who paid") List<@Valid PayerInput> payers,
            @NotEmpty(message = "Choose at least one participant") List<@Valid ShareInput> shares,
            Integer version) {
    }

    public record PayerDto(Long memberId, long amount) {
    }

    public record ShareDto(Long memberId, BigDecimal value, long amount) {
    }

    public record ExpenseDto(Long id, Long groupId, LocalDate expenseDate, String purpose, long amount, Category category,
                             SplitType splitType, String notes, boolean hasReceipt, List<PayerDto> payers,
                             List<ShareDto> shares, Long createdByMemberId, Long updatedByMemberId, Instant createdAt,
                             Instant updatedAt, int version, boolean deleted, Instant deletedAt, long myShare, long myPaid) {
    }

    public record ExpensePage(List<ExpenseDto> items, int page, int size, long total) {
    }

    public record ExpenseFilter(String q, LocalDate from, LocalDate to, Category category, Long payerId, Long participantId) {
    }
}
