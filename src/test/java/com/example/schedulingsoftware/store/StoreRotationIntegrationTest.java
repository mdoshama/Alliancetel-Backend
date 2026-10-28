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
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
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
        oceanside = storeService.create(new StoreRequest(area, "Oceanside", "ocn", null, null, true)).id();
        chulaVista = storeService.create(new StoreRequest(area, "Chula Vista", "CHV", null, null, true)).id();
        otherAreaStore = storeService.create(new StoreRequest(otherArea, "Hollywood", "HWD", null, null, true)).id();
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
                () -> storeService.create(new StoreRequest(area, "oceanside", null, null, null, true)));
        // Same name in a different area is fine.
        storeService.create(new StoreRequest(otherArea, "Oceanside", null, null, null, true));
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
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true));
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
        storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 3, true));
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true));
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
        storeService.update(chulaVista, new StoreRequest(area, "Chula Vista", "CHV", null, 0, true));
        ScheduleResponse s = scheduleService.generate(area, monday);
        int day = firstDayBothWork(s);
        // A manager pins Ana to Chula Vista on one day.
        scheduleService.updateEntry(s.id(), row(s, ana).days().get(day).entryId(), DayType.WORK, chulaVista);

        // Oceanside now needs 2 and Chula Vista needs nothing: Ben should be pulled in where Oceanside is short.
        storeService.update(oceanside, new StoreRequest(area, "Oceanside", "OCN", null, 2, true));
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
        Long unused = storeService.create(new StoreRequest(area, "Pop-up", null, null, null, true)).id();
        storeService.delete(unused);
    }

    private Long employee(String code, Role role, Long storeId) {
        return employee(code, role, storeId, area);
    }

    private Long employee(String code, Role role, Long storeId, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, areaId, storeId, 6, true)).employee().id();
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
