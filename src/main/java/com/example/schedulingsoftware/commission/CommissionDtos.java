package com.example.schedulingsoftware.commission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.schedulingsoftware.commission.CommissionCalculator.Breakdown;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CommissionDtos {

    private CommissionDtos() {
    }

    /** The plan, as shown and edited on the settings screen. */
    public record PlanDto(
            @Min(0) @Max(10000) int activationTier1Max,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal activationTier1Rate,
            @Min(0) @Max(10000) int activationTier2Max,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal activationTier2Rate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal activationTier3Rate,
            @Min(0) @Max(10000) int upgradeTier1Max,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal upgradeTier1Rate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal upgradeTier2Rate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal btsRate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal homeInternetRate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal unlockRate,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal metroLockedRate,
            @NotNull PaysAs freeLinePaysAs,
            @NotNull PaysAs reactivationPaysAs,
            @NotNull @DecimalMin("0") @DecimalMax("99") BigDecimal costPercent,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal accessoryTier1Max,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal accessoryTier1Percent,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal accessoryTier3From,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal accessoryTier2Percent,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal accessoryTier3Percent,
            @NotNull AccessoryMode accessoryMode,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal lateFinePerMinute,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal noCallNoShowFine,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal noUniformFine,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal missedMeetingFine,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal missedSyncMeetingFine,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal scorecardReward,
            Instant updatedAt,
            String updatedBy) {
    }

    public record AdjustmentTypeOption(AdjustmentType value, String label, boolean reward, boolean perMinute,
            BigDecimal defaultAmount) {
    }

    /**
     * Records a fine or reward. {@code amount} is optional (the plan's amount, × minutes for "late"); give it to
     * override. Always a positive number: fines are taken off, rewards added.
     */
    public record AdjustmentRequest(
            @NotNull Long employeeId,
            @NotNull LocalDate date,
            @NotNull AdjustmentType type,
            @Min(1) @Max(1000) Integer quantity,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal amount,
            @Size(max = 255) String note) {
    }

    public record AdjustmentResponse(Long id, Long employeeId, String employeeName, LocalDate date,
            AdjustmentType type, String typeLabel, int quantity, BigDecimal amount, String note, String createdBy,
            Instant createdAt) {
    }

    /**
     * One person's commission for the month.
     *
     * @param pendingBoxes boxes on EODs not approved yet (not paid until approved)
     * @param noCallNoShows no call, no shows this month (3 or more: the sheet says they're let go)
     */
    public record Statement(Long employeeId, String name, String employeeCode, String areaName, String homeStoreName,
            boolean active, Breakdown pay, int pendingBoxes, BigDecimal pendingAccessories, int noCallNoShows,
            List<AdjustmentResponse> adjustments) {
    }

    public record MonthTotals(BigDecimal boxPay, BigDecimal accessoryPay, BigDecimal fines, BigDecimal rewards,
            BigDecimal total, int people) {
    }

    public record MonthResponse(String month, Long areaId, boolean finalized, Instant finalizedAt, String finalizedBy,
            MonthTotals totals, List<Statement> statements,
            /** Things to look at before paying, in plain words. */
            List<String> warnings,
            /** EODs in the month not approved yet (their sales aren't paid until they are). */
            int unapprovedReports) {
    }
}
