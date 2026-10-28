package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Builds a Mon-Sun week for a group of employees.
 *
 * <ol>
 *   <li>Every approved day-off request becomes {@link DayType#REQUESTED_OFF}.</li>
 *   <li>Everyone else is assumed to work, giving a head count for each day.</li>
 *   <li>Employees who still need days off (e.g. a 6-day employee with no request needs one) get them on the
 *       day that currently has the most people working, so coverage stays as even as possible across the week.</li>
 * </ol>
 *
 * A request for more days than the normal weekly off (e.g. two days for a 6-day employee) is honoured; that
 * employee simply works fewer days that week. The generator is deterministic: the same input gives the same week.
 */
@Component
public class ScheduleGenerator {

    public record EmployeeInput(Long employeeId, int workDaysPerWeek, Set<LocalDate> approvedDaysOff) {
    }

    public Map<Long, Map<LocalDate, DayType>> generate(LocalDate weekStart, List<EmployeeInput> employees) {
        LocalDate[] days = new LocalDate[7];
        for (int i = 0; i < 7; i++) {
            days[i] = weekStart.plusDays(i);
        }

        List<EmployeeInput> ordered = employees.stream()
                .sorted(Comparator.comparing(EmployeeInput::employeeId))
                .toList();

        Map<Long, Map<LocalDate, DayType>> result = new LinkedHashMap<>();
        int[] headCount = new int[7];

        // Pass 1: approved requests are fixed; everyone else works for now.
        for (EmployeeInput emp : ordered) {
            Map<LocalDate, DayType> week = new LinkedHashMap<>();
            for (int i = 0; i < 7; i++) {
                boolean requested = emp.approvedDaysOff().contains(days[i]);
                week.put(days[i], requested ? DayType.REQUESTED_OFF : DayType.WORK);
                if (!requested) {
                    headCount[i]++;
                }
            }
            result.put(emp.employeeId(), week);
        }

        // Pass 2: give remaining regular days off where staffing is highest.
        for (int position = 0; position < ordered.size(); position++) {
            EmployeeInput emp = ordered.get(position);
            Map<LocalDate, DayType> week = result.get(emp.employeeId());
            int requested = (int) week.values().stream().filter(t -> t == DayType.REQUESTED_OFF).count();
            int stillNeeded = (7 - clamp(emp.workDaysPerWeek())) - requested;

            for (int n = 0; n < stillNeeded; n++) {
                int best = -1;
                // Start the tie-break at a different weekday for each employee so ties spread out.
                for (int k = 0; k < 7; k++) {
                    int i = (position + k) % 7;
                    if (week.get(days[i]) != DayType.WORK) {
                        continue;
                    }
                    if (best < 0 || headCount[i] > headCount[best]) {
                        best = i;
                    }
                }
                if (best < 0) {
                    break;
                }
                week.put(days[best], DayType.OFF);
                headCount[best]--;
            }
        }
        return result;
    }

    /** Head count of people working each day, keyed by date. */
    public static Map<LocalDate, Integer> coverage(Map<Long, Map<LocalDate, DayType>> week) {
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        week.values().forEach(days -> days.forEach((date, type) ->
                counts.merge(date, type == DayType.WORK ? 1 : 0, Integer::sum)));
        return counts;
    }

    /** Counts of each day type for one employee. */
    public static Map<DayType, Integer> tally(Map<LocalDate, DayType> days) {
        Map<DayType, Integer> counts = new EnumMap<>(DayType.class);
        days.values().forEach(t -> counts.merge(t, 1, Integer::sum));
        return counts;
    }

    private static int clamp(int workDays) {
        return Math.max(0, Math.min(7, workDays));
    }
}
