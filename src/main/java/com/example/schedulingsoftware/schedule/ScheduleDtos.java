package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import java.time.LocalTime;

import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.DayHours;

import jakarta.validation.constraints.NotNull;

public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    public record GenerateScheduleRequest(@NotNull Long areaId, @NotNull LocalDate weekStart) {
    }

    /** Change one day: its type, and for work days the store (null keeps the current one, or the home store). */
    public record UpdateEntryRequest(@NotNull DayType type, Long storeId) {
    }

    /**
     * Drag and drop: the two days trade places, whoever and whenever they are. With {@code storeId}, the dragged work
     * day lands at that store (a drop on a store's row).
     */
    public record ExchangeRequest(@NotNull Long fromEntryId, @NotNull Long toEntryId, Long storeId) {
    }

    /** Two people trade what they are doing on the same day (store and work/off). */
    public record SwapRequest(@NotNull Long entryIdA, @NotNull Long entryIdB) {
    }

    /** Send someone to a store for every day they work this week. */
    public record MoveForWeekRequest(@NotNull Long employeeId, @NotNull Long storeId) {
    }

    /** {@code hours} has seven entries, Monday first. */
    public record StoreRef(Long id, String name, String code, String label, int minStaffPerDay, List<DayHours> hours,
            String color) {
    }

    /** minStaffPerDay is 0 for the "no store assigned" row. */
    public record StoreCoverage(Long storeId, String storeName, String label, int minStaffPerDay,
            List<Integer> dailyHeadCount,
            /** The store's hours on each date of the week (same order as dailyHeadCount). */
            List<DayHours> dailyHours,
            /** The store's color; null for the "no store assigned" row. */
            String color) {
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
            /** The store's color, e.g. "#2563EB"; null without a store. */
            String storeColor,
            /** Moved away from the home store automatically to cover another store's minimum. */
            boolean autoAssigned,
            boolean manuallyEdited,
            /** The store's hours that day, i.e. the shift; null when there is no store. */
            LocalTime opensAt,
            LocalTime closesAt,
            /** The assigned store is closed that day (e.g. hours changed after it was set by hand). */
            boolean storeClosed,
            /** A pending day-off request for this day (managers only), or null. */
            WeekRequest request) {
    }

    /**
     * A day-off request waiting for a decision, shown on the week's schedule.
     *
     * @param canReview the signed-in user may approve it (managers can't decide their own)
     */
    public record WeekRequest(Long id, Long employeeId, String employeeName, LocalDate date, boolean emergency,
            String reason, boolean canReview) {
    }

    public record EmployeeRow(
            Long employeeId,
            String employeeCode,
            String employeeName,
            Role role,
            Long homeStoreId,
            String homeStoreName,
            /** Primary or secondary at the home store, or floater. */
            com.example.schedulingsoftware.employee.StoreRole storeRole,
            int expectedWorkDays,
            int scheduledWorkDays,
            List<DayCell> days) {
    }

    /** How approving a day off would affect staffing. */
    public enum ImpactLevel {
        /** Every store still meets its minimum with people to spare. */
        OK,
        /** Every store still meets its minimum, but with nobody to spare that day. */
        TIGHT,
        /** At least one store would drop below its minimum. */
        SHORT
    }

    /** A store below its minimum on a day. */
    /**
     * Whether one more person can be off in an area on a day without leaving the stores below their minimums.
     *
     * @param needed  people the area's open stores need that day (sum of minimums)
     * @param working people who would still work if this person is off
     * @param offNames others already off or asking (first names)
     * @param full    true when this day off would leave the area short
     */
    public record DayRoom(LocalDate date, int needed, int working, List<String> offNames, boolean full) {
    }

    public record Shortage(String storeName, LocalDate date, int scheduled, int required) {
    }

    public record DayOffImpact(
            Long requestId,
            String employeeName,
            LocalDate date,
            ImpactLevel level,
            /** One sentence for the reviewer. */
            String summary,
            /** People working in the area that day, before and after approving. */
            int workingBefore,
            int workingAfter,
            /** Sum of the minimums of the stores open that day. */
            int neededThatDay,
            /** Their regular day off that becomes a work day instead, so they keep their usual days; may be null. */
            LocalDate replacementWorkDay,
            /** Status of that week's schedule, or null if it has not been made yet. */
            ScheduleStatus scheduleStatus,
            /** Others already approved off that day. */
            List<String> alreadyOff,
            /** Others who also asked for that day and are still waiting. */
            List<String> alsoRequested,
            /** Colleagues at the same home store who are off (or asked to be) that day. */
            List<String> sameStoreOff,
            /** The request was sent as an emergency. */
            boolean emergency,
            /** Store-days that would fall below their minimum, or further below it (any day that week). */
            List<Shortage> newShortages,
            /** Shortages that week that exist anyway, whatever the decision. */
            int existingShortages) {
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
            List<EmployeeRow> rows,
            /** Pending day-off requests for this week, by day (managers only; empty for employees). */
            List<WeekRequest> requests) {
    }
}
