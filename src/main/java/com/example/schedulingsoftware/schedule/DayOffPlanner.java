package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * Applies an approved day off to one person's week.
 *
 * <p>The day becomes {@link DayType#REQUESTED_OFF}. An approved day replaces the regular weekly day off (the same
 * rule {@link ScheduleGenerator} uses), so if the person now works fewer days than usual, one of their regular days
 * off becomes a work day again: the one where the fewest people are working. Days a manager set by hand are never
 * changed.
 */
final class DayOffPlanner {

    private DayOffPlanner() {
    }

    /**
     * @param week            the person's week (changed in place)
     * @param off             the approved date
     * @param workDaysPerWeek how many days they normally work
     * @param fixed           dates a manager set by hand
     * @param working         people working per date in the area (changed in place)
     * @return the regular day off that became a work day, or null if none changed
     */
    static LocalDate takeDayOff(Map<LocalDate, DayType> week, LocalDate off, int workDaysPerWeek,
            Set<LocalDate> fixed, Map<LocalDate, Integer> working) {
        DayType before = week.get(off);
        if (before == null || before == DayType.REQUESTED_OFF) {
            return null;
        }
        week.put(off, DayType.REQUESTED_OFF);
        if (before != DayType.WORK) {
            return null; // It was already a day off; it just becomes the approved one.
        }
        working.merge(off, -1, Integer::sum);
        long workDays = week.values().stream().filter(t -> t == DayType.WORK).count();
        if (workDays >= workDaysPerWeek) {
            return null;
        }
        LocalDate back = week.entrySet().stream()
                .filter(e -> e.getValue() == DayType.OFF)
                .map(Map.Entry::getKey)
                .filter(d -> !fixed.contains(d))
                .min(Comparator.<LocalDate>comparingInt(d -> working.getOrDefault(d, 0)).thenComparing(d -> d))
                .orElse(null);
        if (back != null) {
            week.put(back, DayType.WORK);
            working.merge(back, 1, Integer::sum);
        }
        return back;
    }
}
