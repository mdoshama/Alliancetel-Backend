package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.schedulingsoftware.employee.Role;

import jakarta.validation.constraints.NotNull;

public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    public record GenerateScheduleRequest(@NotNull Long areaId, @NotNull LocalDate weekStart) {
    }

    /** Change one day: its type, and for work days the store (null keeps the current one, or the home store). */
    public record UpdateEntryRequest(@NotNull DayType type, Long storeId) {
    }

    /** Two people trade what they are doing on the same day (store and work/off). */
    public record SwapRequest(@NotNull Long entryIdA, @NotNull Long entryIdB) {
    }

    /** Send someone to a store for every day they work this week. */
    public record MoveForWeekRequest(@NotNull Long employeeId, @NotNull Long storeId) {
    }

    public record StoreRef(Long id, String name, String code, String label, int minStaffPerDay) {
    }

    /** minStaffPerDay is 0 for the "no store assigned" row. */
    public record StoreCoverage(Long storeId, String storeName, String label, int minStaffPerDay,
            List<Integer> dailyHeadCount) {
    }

    /** A store that has fewer people than its minimum on a day. */
    public record CoverageGap(Long storeId, String storeName, LocalDate date, int scheduled, int required) {
    }

    public record DayCell(
            Long entryId,
            LocalDate date,
            DayType type,
            Long storeId,
            String storeName,
            String storeLabel,
            /** Moved away from the home store automatically to cover another store's minimum. */
            boolean autoAssigned,
            boolean manuallyEdited) {
    }

    public record EmployeeRow(
            Long employeeId,
            String employeeCode,
            String employeeName,
            Role role,
            Long homeStoreId,
            String homeStoreName,
            int expectedWorkDays,
            int scheduledWorkDays,
            List<DayCell> days) {
    }

    public record ScheduleResponse(
            Long id,
            Long areaId,
            String areaName,
            LocalDate weekStart,
            LocalDate weekEnd,
            ScheduleStatus status,
            Instant publishedAt,
            Instant updatedAt,
            List<LocalDate> dates,
            List<Integer> dailyHeadCount,
            long pendingDayOffRequests,
            List<StoreRef> stores,
            /** Head count per store per day; a final row with storeId null counts work days with no store. */
            List<StoreCoverage> storeCoverage,
            /** Every store-day below its minimum, in date then store order. Empty means fully covered. */
            List<CoverageGap> coverageGaps,
            List<EmployeeRow> rows) {
    }
}
