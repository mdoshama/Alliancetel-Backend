package com.example.schedulingsoftware.dayoff;

import jakarta.validation.constraints.Pattern;
import com.example.schedulingsoftware.common.InputRules;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DayOffDtos {

    private DayOffDtos() {
    }

    /** One request may cover several dates, e.g. Tuesday and Wednesday of the same week. */
    public record CreateDayOffRequest(
            @NotNull Long employeeId,
            @NotEmpty @Size(max = 7, message = "can have at most 7 days") List<@NotNull LocalDate> dates,
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String reason,
            /** Urgent: allowed even if a colleague at the same store is off, and outside the weekly limit. */
            Boolean emergency,
            /**
             * Managers and admins only, for someone else: give the day(s) off now, approved, skipping the usual
             * checks (full day, same store, weekly limit, request window).
             */
            Boolean override) {

        public CreateDayOffRequest(Long employeeId, List<LocalDate> dates, String reason, Boolean emergency) {
            this(employeeId, dates, reason, emergency, null);
        }
    }

    /** A day someone at the same store already has off (pending or approved), so a normal request can't take it. */
    /**
     * A day that a normal request can't have: a colleague at the same store is off (takenBy, status), or the area
     * has nobody to spare (full = true, takenBy null).
     */
    /**
     * The weekly request window for the day-off page.
     *
     * @param enabled            whether the window applies (people asking for themselves; emergencies are exempt)
     * @param weekStart          Monday of the week people can ask for now
     * @param deadline           the Friday it closes
     * @param nextOpens          the Saturday the following week opens
     * @param emergencyUsedThisMonth the signed-in person already has an emergency request this month
     */
    public record RequestWindow(boolean enabled, LocalDate weekStart, LocalDate weekEnd, LocalDate deadline,
            LocalDate nextOpens, boolean oneEmergencyPerMonth, boolean emergencyUsedThisMonth,
            /** AUTO (Friday cycle), OPEN or CLOSED by an admin, who changed it last and when. */
            PortalMode mode, List<LocalDate> openWeeks, String changedBy, java.time.Instant changedAt,
            /** Days off are reserved for everyone when a week opens. */
            boolean reservations,
            /**
             * The signed-in person's area takes requests up to this date (a manager opened it further ahead), who did,
             * and the last date anyone asking for themselves can pick.
             */
            LocalDate openUntil, String openUntilBy, LocalDate lastDate,
            /**
             * Weeks (Mondays) whose schedule is already published in their area: the day off it gives them there stays,
             * so picking another day that week is an emergency.
             */
            List<LocalDate> publishedWeeks) {
    }

    /** A manager or admin opens an area's requests up to a date, or back to the usual window (null). */
    public record OpenUntilRequest(@NotNull Long areaId, LocalDate until) {
    }

    public record OpenUntilResponse(Long areaId, LocalDate until, String by) {
    }

    public record PortalRequest(@NotNull PortalMode mode) {
    }

    /** Days off a manager gives (e.g. from the suggestions), approved straight away. */
    public record AssignDaysOffRequest(@NotNull Long areaId,
            @NotEmpty @Size(max = 200) List<@NotNull @jakarta.validation.Valid AssignedDay> items) {
    }

    public record AssignedDay(@NotNull Long employeeId, @NotNull LocalDate date) {
    }

    public record TakenDay(LocalDate date, String takenBy, DayOffStatus status, boolean full) {
        public TakenDay(LocalDate date, String takenBy, DayOffStatus status) {
            this(date, takenBy, status, false);
        }

        static TakenDay full(LocalDate date) {
            return new TakenDay(date, null, null, true);
        }
    }

    /**
     * Approve several requests at once.
     *
     * @param onlyIfCovered skip (leave pending) any request that would leave a store below its minimum
     */
    public record ApproveManyRequest(@jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = 200, message = "can have at most 200 requests") List<@NotNull Long> ids,
            Boolean onlyIfCovered) {
    }

    /** A request left pending by "approve all", and why. */
    public record SkippedRequest(Long id, String employeeName, LocalDate date, String reason) {
    }

    public record ApproveManyResult(List<DayOffResponse> approved, List<SkippedRequest> skipped) {
    }

    /** Change an approved or rejected request to the other decision. The reason is required and kept on the request. */
    public record ChangeDecisionRequest(
            @NotNull DayOffStatus status,
            @jakarta.validation.constraints.NotBlank(message = "is required when changing a decision")
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String reason) {
    }

    public record ReviewDayOffRequest(
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String note) {
    }

    public record DayOffResponse(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            Long areaId,
            String areaName,
            LocalDate date,
            DayOfWeek dayOfWeek,
            String reason,
            boolean emergency,
            DayOffStatus status,
            String reviewNote,
            Instant reviewedAt,
            Instant createdAt,
            /** If the decision was changed later: the earlier decision, who changed it, why and when (else null). */
            DayOffStatus previousStatus,
            String decisionChangedBy,
            String decisionChangeReason,
            Instant decisionChangedAt,
            /** After approval: how that week's existing schedule was updated, or null if there was none. */
            String scheduleUpdate,
            /** Reserved by the system; gives way when they ask for another day. */
            boolean reserved,
            /** Reserved because it is one of their regular days off. */
            boolean regular,
            /**
             * Emergencies, for managers and admins: the person's other days off that same week (their regular day,
             * the one the system gave them, days they asked for, other emergencies), so the whole week is in view.
             * Null otherwise.
             */
            List<WeekDayOff> weekDaysOff) {

        public DayOffResponse withScheduleUpdate(String update) {
            return new DayOffResponse(id, employeeId, employeeCode, employeeName, areaId, areaName, date, dayOfWeek,
                    reason, emergency, status, reviewNote, reviewedAt, createdAt, previousStatus, decisionChangedBy,
                    decisionChangeReason, decisionChangedAt, update, reserved, regular, weekDaysOff);
        }

        public DayOffResponse withWeekDaysOff(List<WeekDayOff> days) {
            return new DayOffResponse(id, employeeId, employeeCode, employeeName, areaId, areaName, date, dayOfWeek,
                    reason, emergency, status, reviewNote, reviewedAt, createdAt, previousStatus, decisionChangedBy,
                    decisionChangeReason, decisionChangedAt, scheduleUpdate, reserved, regular, days);
        }

        public static DayOffResponse from(DayOffRequest r) {
            var e = r.getEmployee();
            return new DayOffResponse(
                    r.getId(),
                    e.getId(),
                    e.getEmployeeCode(),
                    e.getFullName(),
                    e.getArea() == null ? null : e.getArea().getId(),
                    e.getArea() == null ? null : e.getArea().getName(),
                    r.getOffDate(),
                    r.getOffDate().getDayOfWeek(),
                    r.getReason(),
                    r.isEmergency(),
                    r.getStatus(),
                    r.getReviewNote(),
                    r.getReviewedAt(),
                    r.getCreatedAt(),
                    r.getPreviousStatus(),
                    r.getDecisionChangedBy(),
                    r.getDecisionChangeReason(),
                    r.getDecisionChangedAt(),
                    null,
                    r.isReserved(),
                    r.isRegular(),
                    null);
        }
    }

    /**
     * Another day off the same person has that week. {@code kind}: REGULAR (their usual day), SYSTEM (the system gave
     * it), ASKED (they asked for it) or EMERGENCY.
     */
    public record WeekDayOff(Long id, LocalDate date, String kind, DayOffStatus status) {

        public static WeekDayOff from(DayOffRequest r) {
            String kind = r.isEmergency() ? "EMERGENCY"
                    : r.isRegular() ? "REGULAR" : r.isReserved() ? "SYSTEM" : "ASKED";
            return new WeekDayOff(r.getId(), r.getOffDate(), kind, r.getStatus());
        }
    }
}
