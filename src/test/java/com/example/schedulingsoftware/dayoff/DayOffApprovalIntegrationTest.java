package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayOffImpact;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ImpactLevel;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Approving a day off after the week's schedule was made, and the staffing check shown before approving. */
@SpringBootTest
@Transactional
class DayOffApprovalIntegrationTest {

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
    Long store;
    Long anna;
    Long ben;
    Long cara;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Approval Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Main", "MAIN", null, 2, true, null, null)).id();
        anna = employee("A-1", "Anna");
        ben = employee("A-2", "Ben");
        cara = employee("A-3", "Cara");
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void approvingAfterTheScheduleWasMadeTakesThemOffThatDay() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell workDay = workDayWithFullStaff(s, anna);
        LocalDate regularOff = row(s, anna).days().stream().filter(c -> c.type() == DayType.OFF).findFirst()
                .orElseThrow().date();

        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null)).getFirst().id();
        DayOffResponse approved = dayOffService.approve(request, null);

        assertNotNull(approved.scheduleUpdate());
        assertTrue(approved.scheduleUpdate().contains("is now off"), approved.scheduleUpdate());
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, day(after, anna, workDay.date()).type());
        assertNull(day(after, anna, workDay.date()).storeId());
        // The approved day replaces her regular day off, so she still works her usual 6 days.
        assertEquals(DayType.WORK, day(after, anna, regularOff).type());
        assertEquals(store, day(after, anna, regularOff).storeId());
        assertEquals(6, row(after, anna).scheduledWorkDays());

        // Auto-fill and manual edits can't put her back to work that day.
        ScheduleResponse filled = scheduleService.autoAssignStores(after.id());
        assertEquals(DayType.REQUESTED_OFF, day(filled, anna, workDay.date()).type());
        Long entry = day(filled, anna, workDay.date()).entryId();
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(after.id(), entry, DayType.WORK, store));
        assertTrue(e.getMessage().contains("approved day off"), e.getMessage());
    }

    @Test
    void approvingWithoutAScheduleChangesNothingYet() {
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(monday.plusDays(2)), null)).getFirst().id();
        assertNull(dayOffService.approve(request, null).scheduleUpdate());
        // Generating later honours it.
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.REQUESTED_OFF, day(s, anna, monday.plusDays(2)).type());
    }

    @Test
    void impactShowsWhetherStoresStayCovered() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate date = workDayWithFullStaff(s, anna).date();
        Long annaRequest = dayOffService.create(new CreateDayOffRequest(anna, List.of(date), null)).getFirst().id();
        dayOffService.create(new CreateDayOffRequest(ben, List.of(date), null));

        // 3 working, minimum 2: after approving, 2 are left for 2 needed.
        DayOffImpact tight = scheduleService.dayOffImpact(annaRequest);
        assertEquals(ImpactLevel.TIGHT, tight.level());
        assertEquals(3, tight.workingBefore());
        assertEquals(2, tight.workingAfter());
        assertEquals(2, tight.neededThatDay());
        assertNotNull(tight.replacementWorkDay());
        assertEquals(1, tight.alsoRequested().size(), "Ben also asked for that day");
        assertTrue(tight.newShortages().isEmpty());

        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 3, true, null, null));
        DayOffImpact shortImpact = scheduleService.dayOffImpact(annaRequest);
        assertEquals(ImpactLevel.SHORT, shortImpact.level());
        assertTrue(shortImpact.newShortages().stream().anyMatch(g -> g.date().equals(date) && g.scheduled() == 2));
        assertTrue(shortImpact.summary().contains("Main"), shortImpact.summary());

        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 1, true, null, null));
        assertEquals(ImpactLevel.OK, scheduleService.dayOffImpact(annaRequest).level());

        // Checking the impact never changes the schedule.
        assertEquals(DayType.WORK, day(scheduleService.find(area, monday).orElseThrow(), anna, date).type());
    }

    @Test
    void aDayThatIsShortEitherWayIsNotCalledCovered() {
        // Minimum 4 with only 3 people: every day is short whatever happens.
        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 4, true, null, null));
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate offDay = row(s, anna).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow().date();
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(offDay), null)).getFirst().id();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        // It is already her day off, so approving changes nothing, but the day is still short.
        assertTrue(impact.newShortages().isEmpty());
        assertTrue(impact.existingShortages() > 0);

        LocalDate workDay = workDayWithFullStaff(s, anna).date();
        Long second = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay), null)).getFirst().id();
        DayOffImpact worse = scheduleService.dayOffImpact(second);
        assertEquals(ImpactLevel.SHORT, worse.level(), "3 of 4 becoming 2 of 4 is worse, even though it was short already");
    }

    @Test
    void impactWorksBeforeTheWeekIsScheduled() {
        Long request = dayOffService.create(new CreateDayOffRequest(cara, List.of(monday.plusDays(4)), null)).getFirst().id();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        assertNull(impact.scheduleStatus());
        assertNotNull(impact.summary());
        assertEquals(2, impact.neededThatDay());
    }

    /** A day she works on which all three people are working. */
    private static DayCell workDayWithFullStaff(ScheduleResponse s, Long employeeId) {
        return row(s, employeeId).days().stream()
                .filter(c -> c.type() == DayType.WORK)
                .filter(c -> s.dailyHeadCount().get(s.dates().indexOf(c.date())) == 3)
                .findFirst().orElseThrow();
    }

    private Long employee(String code, String name) {
        return employeeService.create(new EmployeeRequest(code, name, "Tester", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long employeeId) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow();
    }

    private static DayCell day(ScheduleResponse s, Long employeeId, LocalDate date) {
        return row(s, employeeId).days().stream().filter(c -> c.date().equals(date)).findFirst().orElseThrow();
    }
}
