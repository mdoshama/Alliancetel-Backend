package com.example.schedulingsoftware.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.DayHours;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreDtos.StoreResponse;
import com.example.schedulingsoftware.support.TestUsers;

@SpringBootTest
@Transactional
class StoreRotationIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    ScheduleService scheduleService;

    Long area;
    Long otherArea;
    Long oceanside;
    Long chulaVista;
    Long otherAreaStore;
    Long ana;
    Long ben;
    Long manager;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Store Test Area", null, true)).id();
        otherArea = areaService.create(new AreaRequest("Other Area", null, true)).id();
        oceanside = storeService.create(new StoreRequest(area, "Oceanside", "ocn", null, null, true, null, null)).id();
        chulaVista = storeService.create(new StoreRequest(area, "Chula Vista", "CHV", null, null, true, null, null)).id();
        otherAreaStore = storeService.create(new StoreRequest(otherArea, "Hollywood", "HWD", null, null, true, null, null)).id();
        ana = employee("S-1", Role.EMPLOYEE, oceanside);
        ben = employee("S-2", Role.EMPLOYEE, chulaVista);
        manager = employee("S-3", Role.MANAGER, oceanside);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void storeCodesAreUppercasedAndNamesUniquePerArea() {
        assertEquals("OCN", storeService.getStore(oceanside).getCode());
        assertThrows(BusinessRuleException.class,
                () -> storeService.create(new StoreRequest(area, "oceanside", null, null, null, true, null, null)));
        // Same name in a different area is fine.
        storeService.create(new StoreRequest(otherArea, "Oceanside", null, null, null, true, null, null));
    }

    @Test
    void storesOpenTenToEightUnlessHoursAreSet() {
        StoreResponse s = storeService.list(area).stream().filter(x -> x.id().equals(oceanside)).findFirst().orElseThrow();
        assertEquals(7, s.hours().size());
        assertEquals(java.time.DayOfWeek.MONDAY, s.hours().getFirst().day());
        assertTrue(s.hours().stream().allMatch(h -> !h.closed()
                && h.opensAt().equals(java.time.LocalTime.of(10, 0)) && h.closesAt().equals(java.time.LocalTime.of(20, 0))));
    }

    @Test
    void hoursCanDifferByDayAndBadHoursAreRejected() {
        var t = (java.util.function.Function<String, java.time.LocalTime>) java.time.LocalTime::parse;
        List<DayHours> week = new java.util.ArrayList<>();
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            week.add(d == java.time.DayOfWeek.SUNDAY ? new DayHours(d, t.apply("11:00"), t.apply("19:00"), false)
                    : new DayHours(d, t.apply("09:00"), t.apply("19:00"), false));
        }
        StoreResponse saved = storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, null, true, week, null));
        assertEquals(t.apply("09:00"), saved.hours().getFirst().opensAt());
        assertEquals(t.apply("11:00"), saved.hours().get(6).opensAt());
        assertEquals(t.apply("19:00"), saved.hours().get(6).closesAt());

        // Leaving hours out keeps them.
        StoreResponse kept = storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 2, true, null, null));
        assertEquals(t.apply("11:00"), kept.hours().get(6).opensAt());

        assertThrows(BusinessRuleException.class, () -> storeService.update(oceanside, new StoreRequest(area, "Oceanside",
                "OCN", null, null, true, List.of(new DayHours(java.time.DayOfWeek.MONDAY, t.apply("20:00"), t.apply("10:00"), false)), null)));
        assertThrows(BusinessRuleException.class, () -> storeService.update(oceanside, new StoreRequest(area, "Oceanside",
                "OCN", null, null, true, List.of(new DayHours(java.time.DayOfWeek.MONDAY, null, null, false)), null)));
        assertThrows(BusinessRuleException.class, () -> storeService.update(oceanside, new StoreRequest(area, "Oceanside",
                "OCN", null, null, true, List.of(new DayHours(java.time.DayOfWeek.MONDAY, null, null, true),
                        new DayHours(java.time.DayOfWeek.MONDAY, null, null, true)), null)));
    }

    @Test
    void closedDaysHaveNoMinimumAndNobodyIsSentThere() {
        // Chula Vista needs 1 a day but is closed on Sundays.
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 1, true,
                List.of(new DayHours(java.time.DayOfWeek.SUNDAY, null, null, true)), null));
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate sunday = monday.plusDays(6);
        for (EmployeeRow r : s.rows()) {
            DayCell sun = r.days().stream().filter(c -> c.date().equals(sunday)).findFirst().orElseThrow();
            assertTrue(sun.storeId() == null || !sun.storeId().equals(chulaVista), "nobody at a closed store");
            if (sun.storeId() != null) {
                assertEquals(java.time.LocalTime.of(10, 0), sun.opensAt(), "the shift follows the store's hours");
            }
        }
        assertTrue(s.coverageGaps().stream().noneMatch(g -> g.date().equals(sunday) && g.storeId().equals(chulaVista)));
        var chv = s.storeCoverage().stream().filter(c -> chulaVista.equals(c.storeId())).findFirst().orElseThrow();
        assertTrue(chv.dailyHours().get(6).closed());

        // Setting someone there by hand on Sunday is refused.
        DayCell benSunday = row(s, ben).days().stream().filter(c -> c.date().equals(sunday)).findFirst().orElseThrow();
        assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(s.id(), benSunday.entryId(), DayType.WORK, chulaVista));
    }

    @Test
    void newStoresGetADifferentColorAndColorsCanBeChosen() {
        var stores = storeService.list(area);
        String ocn = stores.stream().filter(x -> x.id().equals(oceanside)).findFirst().orElseThrow().color();
        String chv = stores.stream().filter(x -> x.id().equals(chulaVista)).findFirst().orElseThrow().color();
        assertTrue(ocn.matches("#[0-9A-F]{6}"));
        assertTrue(!ocn.equals(chv), "stores in one area start with different colors");

        StoreResponse saved = storeService.update(oceanside,
                new StoreRequest(area, "Oceanside", "OCN", null, null, true, null, "#ff8800"));
        assertEquals("#FF8800", saved.color());
        // Leaving the color out keeps it.
        assertEquals("#FF8800", storeService.update(oceanside,
                new StoreRequest(area, "Oceanside", "OCN", null, 2, true, null, null)).color());

        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell work = row(s, ana).days().stream().filter(c -> c.storeId() != null && c.storeId().equals(oceanside))
                .findFirst().orElseThrow();
        assertEquals("#FF8800", work.storeColor());
        assertEquals("#FF8800", s.stores().stream().filter(x -> x.id().equals(oceanside)).findFirst().orElseThrow().color());
    }

    @Test
    void homeStoreMustBeInTheEmployeesArea() {
        assertThrows(BusinessRuleException.class, () -> employeeService.changeHomeStore(ana, otherAreaStore));
        assertEquals(chulaVista, employeeService.changeHomeStore(ana, chulaVista).homeStoreId());
        assertNull(employeeService.changeHomeStore(ana, null).homeStoreId());
    }

    @Test
    void generatedScheduleUsesHomeStoresAndCountsPerStore() {
        // No minimum at Chula Vista, so nobody needs to be moved and everyone stays at their home store.
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true, null, null));
        ScheduleResponse s = scheduleService.generate(area, monday);
        for (DayCell cell : row(s, ana).days()) {
            if (cell.type() == DayType.WORK) {
                assertEquals(oceanside, cell.storeId());
                assertEquals("OCN", cell.storeLabel());
            } else {
                assertNull(cell.storeId());
            }
        }
        assertEquals(2, s.stores().size());
        int oceansideShifts = s.storeCoverage().stream().filter(c -> oceanside.equals(c.storeId()))
                .findFirst().orElseThrow().dailyHeadCount().stream().mapToInt(Integer::intValue).sum();
        assertEquals(12, oceansideShifts, "Ana and the manager each work 6 days at Oceanside");
    }

    @Test
    void swapTradesStoresBetweenTwoPeopleOnTheSameDay() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        int day = firstDayBothWork(s);
        DayCell a = row(s, ana).days().get(day);
        DayCell b = row(s, ben).days().get(day);

        ScheduleResponse after = scheduleService.swap(s.id(), a.entryId(), b.entryId());

        assertEquals(chulaVista, row(after, ana).days().get(day).storeId());
        assertEquals(oceanside, row(after, ben).days().get(day).storeId());
        assertTrue(row(after, ana).days().get(day).manuallyEdited());
    }

    @Test
    void swapWithSomeoneOffHandsOverTheShift() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell anaOff = row(s, ana).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow();
        int day = row(s, ana).days().indexOf(anaOff);
        DayCell benSameDay = row(s, ben).days().get(day);
        if (benSameDay.type() != DayType.WORK) {
            return; // Both off the same day in this layout; nothing to hand over.
        }
        ScheduleResponse after = scheduleService.swap(s.id(), anaOff.entryId(), benSameDay.entryId());
        assertEquals(DayType.WORK, row(after, ana).days().get(day).type());
        assertEquals(chulaVista, row(after, ana).days().get(day).storeId());
        assertEquals(DayType.OFF, row(after, ben).days().get(day).type());
    }

    @Test
    void cannotSwapAcrossDaysOrAwayAnApprovedDayOff() {
        Long request = dayOffService.create(new CreateDayOffRequest(ana, List.of(monday), null)).getFirst().id();
        dayOffService.approve(request, null);
        ScheduleResponse s = scheduleService.generate(area, monday);

        DayCell anaMonday = row(s, ana).days().get(0);
        DayCell benMonday = row(s, ben).days().get(0);
        DayCell benTuesday = row(s, ben).days().get(1);
        assertThrows(BusinessRuleException.class, () -> scheduleService.swap(s.id(), anaMonday.entryId(), benMonday.entryId()));
        assertThrows(BusinessRuleException.class,
                () -> scheduleService.swap(s.id(), row(s, ana).days().get(2).entryId(), benTuesday.entryId()));
    }

    @Test
    void moveForWeekSendsSomeoneToAnotherStoreOnAllWorkDays() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        ScheduleResponse after = scheduleService.moveForWeek(s.id(), ana, chulaVista);
        assertTrue(row(after, ana).days().stream()
                .filter(c -> c.type() == DayType.WORK)
                .allMatch(c -> chulaVista.equals(c.storeId())));
        assertThrows(BusinessRuleException.class, () -> scheduleService.moveForWeek(s.id(), ana, otherAreaStore));
    }

    @Test
    void changingADayToWorkDefaultsToHomeStoreAndOffClearsStore() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell off = row(s, ana).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow();
        ScheduleResponse worked = scheduleService.updateEntry(s.id(), off.entryId(), DayType.WORK, null);
        assertEquals(oceanside, cell(worked, ana, off.entryId()).storeId());

        ScheduleResponse moved = scheduleService.updateEntry(s.id(), off.entryId(), DayType.WORK, chulaVista);
        assertEquals(chulaVista, cell(moved, ana, off.entryId()).storeId());

        ScheduleResponse offAgain = scheduleService.updateEntry(s.id(), off.entryId(), DayType.OFF, chulaVista);
        assertNull(cell(offAgain, ana, off.entryId()).storeId());
    }

    @Test
    void storeRolesPrimarySecondaryAndFloater() {
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.SECONDARY,
                employeeService.changeHomeStore(ana, oceanside).storeRole());
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.PRIMARY,
                employeeService.changeHomeStore(ana, oceanside, true).storeRole());
        // Moving a primary's home store keeps them primary; clearing it makes them a floater.
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.PRIMARY,
                employeeService.changeHomeStore(ana, chulaVista).storeRole());
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.FLOATER,
                employeeService.changeHomeStore(ana, null).storeRole());
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.SECONDARY,
                employeeService.changeHomeStore(ana, oceanside).storeRole(), "a floater given a store starts as secondary");
    }

    @Test
    void generateAndAutoFillNeverMoveAPrimary() {
        // Oceanside: Ana (primary) and the manager. Chula Vista needs 3 but only has Ben.
        employeeService.changeHomeStore(ana, oceanside, true);
        storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 0, true, null, null));
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 3, true, null, null));
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.PRIMARY, row(s, ana).storeRole());
        for (DayCell c : row(s, ana).days()) {
            if (c.type() == DayType.WORK) {
                assertEquals(oceanside, c.storeId(), "the primary stays at Oceanside on " + c.date());
                assertTrue(!c.autoAssigned());
            }
        }
        ScheduleResponse filled = scheduleService.autoAssignStores(s.id());
        assertTrue(row(filled, ana).days().stream().filter(c -> c.type() == DayType.WORK)
                .allMatch(c -> oceanside.equals(c.storeId())));
        assertTrue(!filled.coverageGaps().isEmpty(), "Chula Vista is reported short instead");

        // A manager can still move them by hand.
        DayCell work = row(filled, ana).days().stream().filter(c -> c.type() == DayType.WORK).findFirst().orElseThrow();
        assertEquals(chulaVista, cell(scheduleService.updateEntry(s.id(), work.entryId(), DayType.WORK, chulaVista),
                ana, work.entryId()).storeId());
    }

    @Test
    void aWorkDayNeedsAStore() {
        employeeService.changeHomeStore(ana, null);
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell off = row(s, ana).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow();
        // No home store to fall back on, so the store must be given.
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(s.id(), off.entryId(), DayType.WORK, null));
        assertTrue(e.getMessage().contains("Pick the store"), e.getMessage());
        assertEquals(chulaVista, cell(scheduleService.updateEntry(s.id(), off.entryId(), DayType.WORK, chulaVista),
                ana, off.entryId()).storeId());
    }

    @Test
    void managersRotateOnlyTheirOwnAreaAndEmployeesCannot() {
        Long otherEmployee = employee("S-9", Role.EMPLOYEE, null, otherArea);

        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertEquals(chulaVista, employeeService.changeHomeStore(ana, chulaVista).homeStoreId());
        assertThrows(AccessDeniedException.class, () -> employeeService.changeHomeStore(otherEmployee, otherAreaStore));
        assertEquals(List.of(oceanside, chulaVista).size(), storeService.list(null).size());
        assertThrows(AccessDeniedException.class, () -> storeService.list(otherArea));

        TestUsers.signInAs(ana, Role.EMPLOYEE, area);
        assertThrows(AccessDeniedException.class, () -> employeeService.changeHomeStore(ana, oceanside));
    }

    @Test
    void storesBelowMinimumAreFilledAutomaticallyAndRemainingGapsReported() {
        // Oceanside needs 3 but only Ana and the manager are based there; Chula Vista (Ben) needs nobody.
        storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 3, true, null, null));
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true, null, null));
        ScheduleResponse s = scheduleService.generate(area, monday);

        for (int day = 0; day < 7; day++) {
            DayCell benDay = row(s, ben).days().get(day);
            if (benDay.type() == DayType.WORK) {
                assertEquals(oceanside, benDay.storeId(), "Ben is sent to Oceanside on days he works");
                assertTrue(benDay.autoAssigned());
            }
        }
        // Gaps remain only on days when fewer than 3 of the 3 staff work.
        for (var gap : s.coverageGaps()) {
            assertEquals(oceanside, gap.storeId());
            int working = (int) s.rows().stream()
                    .filter(r -> r.days().stream().anyMatch(c -> c.date().equals(gap.date()) && c.type() == DayType.WORK))
                    .count();
            assertTrue(working < 3);
            assertEquals(working, gap.scheduled());
        }
        assertTrue(s.coverageGaps().size() < 7);
    }

    @Test
    void autoFillKeepsManualChangesAndAppliesNewMinimums() {
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true, null, null));
        ScheduleResponse s = scheduleService.generate(area, monday);
        int day = firstDayBothWork(s);
        // A manager pins Ana to Chula Vista on one day.
        scheduleService.updateEntry(s.id(), row(s, ana).days().get(day).entryId(), DayType.WORK, chulaVista);

        // Oceanside now needs 2 and Chula Vista needs nothing: Ben should be pulled in where Oceanside is short.
        storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 2, true, null, null));
        ScheduleResponse after = scheduleService.autoAssignStores(s.id());

        DayCell pinned = row(after, ana).days().get(day);
        assertEquals(chulaVista, pinned.storeId(), "manual change kept");
        assertTrue(pinned.manuallyEdited());
        assertEquals(oceanside, row(after, ben).days().get(day).storeId(), "Ben covers Oceanside that day");
        assertTrue(row(after, ben).days().get(day).autoAssigned());
    }

    @Test
    void newStoresDefaultToAMinimumOfOne() {
        assertEquals(1, storeService.getStore(oceanside).getMinStaffPerDay());
    }

    @Test
    void usedStoresCannotBeDeleted() {
        assertThrows(BusinessRuleException.class, () -> storeService.delete(oceanside));
        Long unused = storeService.create(new StoreRequest(area, "Pop-up", null, null, null, true, null, null)).id();
        storeService.delete(unused);
    }

    private Long employee(String code, Role role, Long storeId) {
        return employee(code, role, storeId, area);
    }

    private Long employee(String code, Role role, Long storeId, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, areaId, storeId, 6, true, null)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long employeeId) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow();
    }

    private static DayCell cell(ScheduleResponse s, Long employeeId, Long entryId) {
        return row(s, employeeId).days().stream().filter(c -> c.entryId().equals(entryId)).findFirst().orElseThrow();
    }

    private int firstDayBothWork(ScheduleResponse s) {
        for (int i = 0; i < 7; i++) {
            if (row(s, ana).days().get(i).type() == DayType.WORK && row(s, ben).days().get(i).type() == DayType.WORK) {
                return i;
            }
        }
        throw new IllegalStateException("Ana and Ben never work the same day");
    }
}
