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
            Boolean emergency) {
    }

    /** A day someone at the same store already has off (pending or approved), so a normal request can't take it. */
    /**
     * A day that a normal request can't have: a colleague at the same store is off (takenBy, status), or the area
     * has nobody to spare (full = true, takenBy null).
     */
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
            String scheduleUpdate) {

        public DayOffResponse withScheduleUpdate(String update) {
            return new DayOffResponse(id, employeeId, employeeCode, employeeName, areaId, areaName, date, dayOfWeek,
                    reason, emergency, status, reviewNote, reviewedAt, createdAt, previousStatus, decisionChangedBy,
                    decisionChangeReason, decisionChangedAt, update);
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
                    null);
        }
    }
}
