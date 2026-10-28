package com.example.schedulingsoftware.dayoff;

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
            @NotEmpty @Size(max = 7) List<@NotNull LocalDate> dates,
            @Size(max = 255) String reason,
            /** Urgent: allowed even if a colleague at the same store is off, and outside the weekly limit. */
            Boolean emergency) {
    }

    /** A day someone at the same store already has off (pending or approved), so a normal request can't take it. */
    public record TakenDay(LocalDate date, String takenBy, DayOffStatus status) {
    }

    public record ReviewDayOffRequest(@Size(max = 255) String note) {
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
            /** After approval: how that week's existing schedule was updated, or null if there was none. */
            String scheduleUpdate) {

        public DayOffResponse withScheduleUpdate(String update) {
            return new DayOffResponse(id, employeeId, employeeCode, employeeName, areaId, areaName, date, dayOfWeek,
                    reason, emergency, status, reviewNote, reviewedAt, createdAt, update);
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
                    null);
        }
    }
}
