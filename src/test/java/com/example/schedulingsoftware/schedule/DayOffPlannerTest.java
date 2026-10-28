package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DayOffPlannerTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @Test
    void approvedDayReplacesTheRegularDayOff() {
        Map<LocalDate, DayType> week = week(3); // off Thursday
        Map<LocalDate, Integer> working = new HashMap<>(Map.of(MON.plusDays(3), 4));
        LocalDate back = DayOffPlanner.takeDayOff(week, MON.plusDays(1), 6, Set.of(), working);
        assertEquals(MON.plusDays(3), back);
        assertEquals(DayType.REQUESTED_OFF, week.get(MON.plusDays(1)));
        assertEquals(DayType.WORK, week.get(MON.plusDays(3)));
        assertEquals(5, working.get(MON.plusDays(3)));
    }

    @Test
    void aDayOffSetByHandIsNotTurnedIntoWork() {
        Map<LocalDate, DayType> week = week(3);
        assertNull(DayOffPlanner.takeDayOff(week, MON.plusDays(1), 6, Set.of(MON.plusDays(3)), new HashMap<>()));
        assertEquals(DayType.OFF, week.get(MON.plusDays(3)));
        assertEquals(DayType.REQUESTED_OFF, week.get(MON.plusDays(1)));
    }

    @Test
    void approvingARegularDayOffJustRelabelsIt() {
        Map<LocalDate, DayType> week = week(3);
        assertNull(DayOffPlanner.takeDayOff(week, MON.plusDays(3), 6, Set.of(), new HashMap<>()));
        assertEquals(DayType.REQUESTED_OFF, week.get(MON.plusDays(3)));
        assertEquals(6, week.values().stream().filter(t -> t == DayType.WORK).count());
    }

    @Test
    void fewerWorkDaysThanUsualOnlyWhenNoRegularDayOffIsLeft() {
        Map<LocalDate, DayType> week = week(3);
        DayOffPlanner.takeDayOff(week, MON, 6, Set.of(), new HashMap<>());       // Thursday becomes work
        LocalDate back = DayOffPlanner.takeDayOff(week, MON.plusDays(1), 6, Set.of(), new HashMap<>());
        assertNull(back, "no regular day off left to give back");
        assertEquals(5, week.values().stream().filter(t -> t == DayType.WORK).count());
    }

    private static Map<LocalDate, DayType> week(int offIndex) {
        Map<LocalDate, DayType> w = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            w.put(MON.plusDays(i), i == offIndex ? DayType.OFF : DayType.WORK);
        }
        return w;
    }
}
