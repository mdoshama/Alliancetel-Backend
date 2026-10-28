package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.schedulingsoftware.schedule.StoreAssigner.Assignment;
import com.example.schedulingsoftware.schedule.StoreAssigner.StoreSlot;
import com.example.schedulingsoftware.schedule.StoreAssigner.WorkSlot;

class StoreAssignerTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final long A = 10L;
    private static final long B = 20L;

    private final StoreAssigner assigner = new StoreAssigner();

    @Test
    void everyoneStaysHomeWhenMinimumsAreMet() {
        var result = assigner.assign(stores(A, 1, B, 1),
                List.of(slot(1, MON, A), slot(2, MON, B)));
        assertEquals(new Assignment(A, false), result.get("1-" + MON));
        assertEquals(new Assignment(B, false), result.get("2-" + MON));
    }

    @Test
    void peopleWithoutAStoreGoWhereTheyAreNeeded() {
        var result = assigner.assign(stores(A, 1, B, 2),
                List.of(slot(1, MON, A), slot(2, MON, B), slot(3, MON, null)));
        assertEquals(B, result.get("3-" + MON).storeId(), "B is the store still short");
        assertTrue(result.get("3-" + MON).auto());
    }

    @Test
    void withoutShortagesUnassignedPeopleGoToTheLeastStaffedStore() {
        var result = assigner.assign(stores(A, 1, B, 1),
                List.of(slot(1, MON, A), slot(2, MON, A), slot(3, MON, B), slot(4, MON, null)));
        assertEquals(B, result.get("4-" + MON).storeId());
    }

    @Test
    void aThirdPersonMovesFromAStoreWithExtraToAShortStore() {
        // A needs 2 and has 3; B needs 2 and has 1.
        var result = assigner.assign(stores(A, 2, B, 2),
                List.of(slot(1, MON, A), slot(2, MON, A), slot(3, MON, A), slot(4, MON, B)));
        assertEquals(2, countAt(result, A));
        assertEquals(2, countAt(result, B));
        long moved = result.values().stream().filter(Assignment::auto).count();
        assertEquals(1, moved, "exactly one person is moved");
    }

    @Test
    void aDonorStoreNeverDropsBelowItsOwnMinimum() {
        // Both stores need 2; A has 2, B has 1. Nobody can be spared, so B stays short.
        var result = assigner.assign(stores(A, 2, B, 2),
                List.of(slot(1, MON, A), slot(2, MON, A), slot(3, MON, B)));
        assertEquals(2, countAt(result, A));
        assertEquals(1, countAt(result, B));
        assertTrue(result.values().stream().noneMatch(Assignment::auto));
    }

    @Test
    void managersAreNotMovedAwayFromTheirStore() {
        List<WorkSlot> slots = List.of(manager(1, MON, A), manager(2, MON, A));
        var result = assigner.assign(stores(A, 1, B, 1), slots);
        assertEquals(2, countAt(result, A));
        assertEquals(0, countAt(result, B));
    }

    @Test
    void daysSetByHandAreKeptAndCountTowardsTheirStore() {
        // Person 3 was put at B by a manager, so B is already covered and nobody needs to move.
        var result = assigner.assign(stores(A, 1, B, 1),
                List.of(slot(1, MON, A), slot(2, MON, A), locked(3, MON, A, B)));
        assertEquals(new Assignment(B, false), result.get("3-" + MON));
        assertEquals(2, countAt(result, A));
        assertTrue(result.values().stream().noneMatch(Assignment::auto));
    }

    @Test
    void rotationIsSharedSoTheSamePersonIsNotMovedEveryDay() {
        // A has three people and needs one; B has nobody and needs one, every day for three days.
        List<WorkSlot> slots = new ArrayList<>();
        for (int d = 0; d < 3; d++) {
            for (int p = 1; p <= 3; p++) {
                slots.add(slot(p, MON.plusDays(d), A));
            }
        }
        var result = assigner.assign(stores(A, 1, B, 1), slots);
        Set<String> movedPeople = new HashSet<>();
        result.forEach((key, a) -> {
            if (a.auto()) {
                movedPeople.add(((String) key).split("-")[0]);
            }
        });
        assertEquals(Set.of("1", "2", "3"), movedPeople, "a different person covers B each day");
    }

    @Test
    void sameInputGivesTheSameAssignment() {
        List<WorkSlot> slots = List.of(slot(5, MON, A), slot(3, MON, A), slot(4, MON, null), slot(1, MON, B));
        var first = assigner.assign(stores(A, 2, B, 2), slots);
        var second = assigner.assign(stores(B, 2, A, 2), new ArrayList<>(slots).reversed());
        assertEquals(first, second);
    }

    @Test
    void noStoresMeansNoAssignment() {
        var result = assigner.assign(List.of(), List.of(slot(1, MON, null)));
        assertNull(result.get("1-" + MON).storeId());
        assertFalse(result.get("1-" + MON).auto());
    }

    private static List<StoreSlot> stores(long id1, int min1, long id2, int min2) {
        return List.of(new StoreSlot(id1, min1), new StoreSlot(id2, min2));
    }

    private static WorkSlot slot(long employee, LocalDate date, Long home) {
        return new WorkSlot(employee + "-" + date, employee, date, home, false, false, null);
    }

    private static WorkSlot manager(long employee, LocalDate date, Long home) {
        return new WorkSlot(employee + "-" + date, employee, date, home, true, false, null);
    }

    private static WorkSlot locked(long employee, LocalDate date, Long home, Long store) {
        return new WorkSlot(employee + "-" + date, employee, date, home, false, true, store);
    }

    private static long countAt(Map<Object, Assignment> result, long store) {
        return result.values().stream().filter(a -> a.storeId() != null && a.storeId() == store).count();
    }
}
