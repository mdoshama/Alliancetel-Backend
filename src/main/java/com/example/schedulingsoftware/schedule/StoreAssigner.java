package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Decides which store each person works at on each work day, so stores meet their minimum staffing.
 *
 * <p>For every day:
 * <ol>
 *   <li>Days a manager set by hand are kept exactly as they are.</li>
 *   <li>Everyone else starts at their home store.</li>
 *   <li>People without a home store go to the store furthest below its minimum (or, if none is short,
 *       the least-staffed store).</li>
 *   <li>While a store is still below its minimum, one person is moved in from a store that has more than its
 *       minimum (the "third person"). The donor store never drops below its own minimum, managers are never
 *       moved away from their store, and moves are spread so the same person is not rotated every day.</li>
 * </ol>
 * If the area simply does not have enough people working that day, the remaining gaps are left for the
 * schedule to report. The result is deterministic: the same input always gives the same assignment.
 */
@Component
public class StoreAssigner {

    /** A store that can be assigned, with its minimum people per day (0 = no minimum). */
    public record StoreSlot(Long storeId, int minStaffPerDay) {
    }

    /**
     * One person working one day.
     *
     * @param key           caller's id for the slot (e.g. the schedule entry id)
     * @param homeStoreId   active home store, or null
     * @param locked        true when a manager set this day by hand; it is never changed
     * @param lockedStoreId the store of a locked slot (may be null)
     */
    public record WorkSlot(Object key, Long employeeId, LocalDate date, Long homeStoreId, boolean manager,
            boolean locked, Long lockedStoreId) {
    }

    /** Where a slot ends up; {@code auto} is true when it is not the person's home store and not a manual choice. */
    public record Assignment(Long storeId, boolean auto) {
    }

    public Map<Object, Assignment> assign(List<StoreSlot> stores, List<WorkSlot> slots) {
        Map<Object, Assignment> result = new LinkedHashMap<>();
        Map<Long, Integer> movesPerEmployee = new HashMap<>();
        Map<Long, Integer> minimums = new LinkedHashMap<>();
        stores.stream().sorted(Comparator.comparing(StoreSlot::storeId))
                .forEach(s -> minimums.put(s.storeId(), Math.max(0, s.minStaffPerDay())));

        Map<LocalDate, List<WorkSlot>> byDay = new TreeMap<>();
        slots.forEach(s -> byDay.computeIfAbsent(s.date(), d -> new ArrayList<>()).add(s));

        for (List<WorkSlot> day : byDay.values()) {
            assignDay(day, minimums, movesPerEmployee, result);
        }
        return result;
    }

    private void assignDay(List<WorkSlot> day, Map<Long, Integer> minimums, Map<Long, Integer> moves,
            Map<Object, Assignment> result) {
        Map<Long, Integer> count = new HashMap<>();
        minimums.keySet().forEach(id -> count.put(id, 0));
        Map<Object, Long> current = new HashMap<>();
        List<WorkSlot> movable = new ArrayList<>();
        List<WorkSlot> homeless = new ArrayList<>();

        List<WorkSlot> ordered = day.stream().sorted(Comparator.comparing(WorkSlot::employeeId)).toList();
        for (WorkSlot s : ordered) {
            if (s.locked()) {
                result.put(s.key(), new Assignment(s.lockedStoreId(), false));
                count.computeIfPresent(s.lockedStoreId(), (k, v) -> v + 1);
            } else if (s.homeStoreId() != null && minimums.containsKey(s.homeStoreId())) {
                current.put(s.key(), s.homeStoreId());
                count.merge(s.homeStoreId(), 1, Integer::sum);
                movable.add(s);
            } else {
                homeless.add(s);
            }
        }

        // People without a usable home store go where they are needed most.
        for (WorkSlot s : homeless) {
            Long target = neediestStore(minimums, count);
            current.put(s.key(), target);
            if (target != null) {
                count.merge(target, 1, Integer::sum);
                movable.add(s);
            }
        }

        // Move surplus people into stores that are still below their minimum.
        while (true) {
            Long shortStore = mostShortStore(minimums, count);
            if (shortStore == null) {
                break;
            }
            WorkSlot donor = movable.stream()
                    .filter(s -> !s.manager())
                    .filter(s -> !shortStore.equals(current.get(s.key())))
                    .filter(s -> current.get(s.key()) != null)
                    .filter(s -> count.get(current.get(s.key())) > minimums.get(current.get(s.key())))
                    .min(Comparator.<WorkSlot>comparingInt(s -> moves.getOrDefault(s.employeeId(), 0))
                            .thenComparing(s -> -(count.get(current.get(s.key())) - minimums.get(current.get(s.key()))))
                            .thenComparing(WorkSlot::employeeId))
                    .orElse(null);
            if (donor == null) {
                break; // Not enough people in the area today; the gap stays and is reported.
            }
            Long from = current.get(donor.key());
            count.merge(from, -1, Integer::sum);
            count.merge(shortStore, 1, Integer::sum);
            current.put(donor.key(), shortStore);
            moves.merge(donor.employeeId(), 1, Integer::sum);
        }

        for (WorkSlot s : ordered) {
            if (!s.locked()) {
                Long store = current.get(s.key());
                result.put(s.key(), new Assignment(store, store != null && !store.equals(s.homeStoreId())));
            }
        }
    }

    /** The store furthest below its minimum, or if none is short, the least-staffed store relative to its minimum. */
    private static Long neediestStore(Map<Long, Integer> minimums, Map<Long, Integer> count) {
        Long shortStore = mostShortStore(minimums, count);
        if (shortStore != null) {
            return shortStore;
        }
        return minimums.keySet().stream()
                .min(Comparator.<Long>comparingDouble(id -> count.get(id) / (double) Math.max(1, minimums.get(id)))
                        .thenComparingInt(count::get)
                        .thenComparing(id -> id))
                .orElse(null);
    }

    private static Long mostShortStore(Map<Long, Integer> minimums, Map<Long, Integer> count) {
        return minimums.keySet().stream()
                .filter(id -> count.get(id) < minimums.get(id))
                .max(Comparator.<Long>comparingInt(id -> minimums.get(id) - count.get(id))
                        .thenComparing(Comparator.<Long>naturalOrder().reversed()))
                .orElse(null);
    }
}
