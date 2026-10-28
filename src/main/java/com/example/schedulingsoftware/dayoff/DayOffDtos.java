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
            @Size(max = 255) String reason) {
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
            DayOffStatus status,
            String reviewNote,
            Instant reviewedAt,
            Instant createdAt) {

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
                    r.getStatus(),
                    r.getReviewNote(),
                    r.getReviewedAt(),
                    r.getCreatedAt());
        }
    }
}
