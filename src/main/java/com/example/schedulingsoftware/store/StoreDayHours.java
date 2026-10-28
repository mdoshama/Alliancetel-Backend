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

    private StoreDayHours(DayOfWeek dayOfWeek, LocalTime opensAt, LocalTime closesAt, boolean closed) {
        this.dayOfWeek = dayOfWeek;
        this.opensAt = closed ? null : opensAt;
        this.closesAt = closed ? null : closesAt;
        this.closed = closed;
    }

    public static StoreDayHours open(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) {
        return new StoreDayHours(day, opensAt, closesAt, false);
    }

    public static StoreDayHours closedOn(DayOfWeek day) {
        return new StoreDayHours(day, null, null, true);
    }
}
