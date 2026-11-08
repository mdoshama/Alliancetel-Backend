package com.example.schedulingsoftware.timeclock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.example.schedulingsoftware.common.InputRules;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class TimeClockDtos {

    private TimeClockDtos() {
    }

    /**
     * One person on the day's sheet.
     *
     * @param scheduled  on the published schedule to work that day
     * @param opensAt    when their store opens that day (for "late")
     * @param minutesLate minutes after opening (plus grace) they were clocked in; 0 when on time
     * @param missing    scheduled, the store has been open past the grace period, and nobody clocked them in
     * @param hoursWorked when clocked out: hours between clock in and out
     */
    public record Row(Long employeeId, String name, String employeeCode, String areaName, Long storeId,
            String storeName, String storeColor, boolean scheduled, LocalTime opensAt, Long entryId, LocalTime clockIn,
            LocalTime clockOut, String clockedInBy, String clockedOutBy, String note, int minutesLate,
            boolean missing, Double hoursWorked) {
    }

    public record Day(LocalDate date, boolean today, int scheduled, int clockedIn, int missing, int late,
            boolean canEdit, int graceMinutes, List<Row> rows, List<Row> others) {
    }

    /** Clocking someone in; time defaults to now, store to where they're scheduled. */
    public record ClockInRequest(@NotNull Long employeeId, @NotNull LocalDate date, LocalTime time, Long storeId,
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String note) {
    }

    /** Clocking out (optional); time defaults to now. */
    public record ClockOutRequest(LocalTime time) {
    }

    /** Correcting an entry: clock-out may be cleared (null). */
    public record CorrectRequest(@NotNull LocalTime clockIn, LocalTime clockOut,
            @Size(max = 255) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String note) {
    }
}
