package com.example.schedulingsoftware.store;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A store's opening hours on one day of the week, e.g. Sunday 11:00–19:00, or closed. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreDayHours {

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 9)
    private DayOfWeek dayOfWeek;

    @Column(name = "opens_at")
    private LocalTime opensAt;

    @Column(name = "closes_at")
    private LocalTime closesAt;

    @Column(nullable = false)
    private boolean closed;

    /**
     * Fewest people needed on this weekday, when it differs from the store's usual minimum (e.g. 3 all week but 2 on
     * Sunday). Null uses the store's minimum.
     */
    @Column(name = "min_staff")
    private Integer minStaff;

    private StoreDayHours(DayOfWeek dayOfWeek, LocalTime opensAt, LocalTime closesAt, boolean closed, Integer minStaff) {
        this.dayOfWeek = dayOfWeek;
        this.opensAt = closed ? null : opensAt;
        this.closesAt = closed ? null : closesAt;
        this.closed = closed;
        this.minStaff = closed ? null : minStaff;
    }

    public static StoreDayHours open(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) {
        return new StoreDayHours(day, opensAt, closesAt, false, null);
    }

    /** Open, with its own minimum staff for the day (null = the store's usual minimum). */
    public static StoreDayHours open(DayOfWeek day, LocalTime opensAt, LocalTime closesAt, Integer minStaff) {
        return new StoreDayHours(day, opensAt, closesAt, false, minStaff);
    }

    public static StoreDayHours closedOn(DayOfWeek day) {
        return new StoreDayHours(day, null, null, true, null);
    }
}
