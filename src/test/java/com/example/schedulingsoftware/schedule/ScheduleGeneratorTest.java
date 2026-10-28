package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.schedulingsoftware.schedule.ScheduleGenerator.EmployeeInput;

class ScheduleGeneratorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private final ScheduleGenerator generator = new ScheduleGenerator();

    @Test
    void sixDayEmployeeWithoutRequestGetsExactlyOneDayOff() {
        var week = generator.generate(MONDAY, List.of(new EmployeeInput(1L, 6, Set.of()))).get(1L);

        assertEquals(6, count(week, DayType.WORK));
        assertEquals(1, count(week, DayType.OFF));
    }

    @Test
    void requestedDayReplacesTheRegularDayOff() {
        LocalDate wednesday = MONDAY.plusDays(2);
        var week = generator.generate(MONDAY, List.of(new EmployeeInput(1L, 6, Set.of(wednesday)))).get(1L);

        assertEquals(DayType.REQUESTED_OFF, week.get(wednesday));
        assertEquals(6, count(week, DayType.WORK));
        assertEquals(0, count(week, DayType.OFF));
    }

    @Test
    void twoRequestedDaysAreBothHonoured() {
        Set<LocalDate> friSat = Set.of(MONDAY.plusDays(4), MONDAY.plusDays(5));
        var week = generator.generate(MONDAY, List.of(new EmployeeInput(1L, 6, friSat))).get(1L);

        assertEquals(2, count(week, DayType.REQUESTED_OFF));
        assertEquals(5, count(week, DayType.WORK));
    }

    @Test
    void regularDaysOffAreSpreadEvenlyAcrossTheWeek() {
        List<EmployeeInput> staff = new ArrayList<>();
        for (long id = 1; id <= 14; id++) {
            staff.add(new EmployeeInput(id, 6, Set.of()));
        }
        var plan = generator.generate(MONDAY, staff);
        Map<LocalDate, Integer> coverage = ScheduleGenerator.coverage(plan);

        // 14 people x 6 days = 84 shifts over 7 days -> exactly 12 per day.
        coverage.values().forEach(n -> assertEquals(12, n));
    }

    @Test
    void autoAssignedDaysOffAvoidDaysAlreadyShortStaffed() {
        LocalDate saturday = MONDAY.plusDays(5);
        List<EmployeeInput> staff = List.of(
                new EmployeeInput(1L, 6, Set.of(saturday)),
                new EmployeeInput(2L, 6, Set.of(saturday)),
                new EmployeeInput(3L, 6, Set.of()),
                new EmployeeInput(4L, 6, Set.of()));
        var plan = generator.generate(MONDAY, staff);

        assertTrue(plan.get(3L).get(saturday) == DayType.WORK);
        assertTrue(plan.get(4L).get(saturday) == DayType.WORK);
    }

    @Test
    void sameInputAlwaysGivesSameSchedule() {
        List<EmployeeInput> staff = new ArrayList<>();
        for (long id = 1; id <= 9; id++) {
            staff.add(new EmployeeInput(id, 6, Set.of()));
        }
        var first = generator.generate(MONDAY, staff);
        Collections.reverse(staff);
        var second = generator.generate(MONDAY, staff);

        assertEquals(first, second);
    }

    private static long count(Map<LocalDate, DayType> week, DayType type) {
        return week.values().stream().filter(t -> t == type).count();
    }
}
