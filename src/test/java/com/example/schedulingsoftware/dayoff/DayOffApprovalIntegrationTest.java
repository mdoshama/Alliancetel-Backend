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

        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null, null)).getFirst().id();
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
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(monday.plusDays(2)), null, null)).getFirst().id();
        assertNull(dayOffService.approve(request, null).scheduleUpdate());
        // Generating later honours it.
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.REQUESTED_OFF, day(s, anna, monday.plusDays(2)).type());
    }

    @Test
    void impactShowsWhetherStoresStayCovered() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate date = workDayWithFullStaff(s, anna).date();
        Long annaRequest = dayOffService.create(new CreateDayOffRequest(anna, List.of(date), null, null)).getFirst().id();
        // A normal request for the same day at the same store is refused; an emergency one is allowed.
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(date), null, null)));
        dayOffService.create(new CreateDayOffRequest(ben, List.of(date), "Family emergency", true));

        // 3 working, minimum 2: after approving, 2 are left for 2 needed.
        DayOffImpact tight = scheduleService.dayOffImpact(annaRequest);
        assertEquals(ImpactLevel.TIGHT, tight.level());
        assertEquals(3, tight.workingBefore());
        assertEquals(2, tight.workingAfter());
        assertEquals(2, tight.neededThatDay());
        assertNotNull(tight.replacementWorkDay());
        assertEquals(1, tight.alsoRequested().size(), "Ben also asked for that day");
        assertEquals(1, tight.sameStoreOff().size(), "Ben is at the same store");
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
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(offDay), null, null)).getFirst().id();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        // It is already her day off, so approving changes nothing, but the day is still short.
        assertTrue(impact.newShortages().isEmpty());
        assertTrue(impact.existingShortages() > 0);

        LocalDate workDay = workDayWithFullStaff(s, anna).date();
        Long second = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay), null, null)).getFirst().id();
        DayOffImpact worse = scheduleService.dayOffImpact(second);
        assertEquals(ImpactLevel.SHORT, worse.level(), "3 of 4 becoming 2 of 4 is worse, even though it was short already");
    }

    @Test
    void onlyOnePersonPerStoreCanBeOffOnADay() {
        LocalDate wed = monday.plusDays(2);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null));

        // Ben is at the same store: refused, with a message naming who has it.
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, null)));
        assertTrue(e.getMessage().contains("Anna already has"), e.getMessage());
        assertEquals(1, dayOffService.takenDays(ben, monday, monday.plusDays(6)).size());

        // Another day is fine; so is the same day at another store or for a floater.
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wed.plusDays(1)), null, null));
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 1, true, null, null)).id();
        employeeService.changeHomeStore(cara, other);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(wed), null, null));
        Long floater = employeeService.create(new EmployeeRequest("A-9", "Fay", "Floater", "a-9@test.example", null,
                Role.EMPLOYEE, area, null, 6, true, null)).employee().id();
        dayOffService.create(new CreateDayOffRequest(floater, List.of(wed), null, null));
        assertTrue(dayOffService.takenDays(floater, monday, monday.plusDays(6)).isEmpty());

        // A rejected request frees the day again.
        Long annaThu = dayOffService.create(new CreateDayOffRequest(anna, List.of(wed.plusDays(2)), null, null))
                .getFirst().id();
        dayOffService.reject(annaThu, "Busy");
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wed.plusDays(2)), null, null));
    }

    @Test
    void emergencyRequestsSkipTheStoreRuleAndWeeklyLimitButNeedApproval() {
        LocalDate tue = monday.plusDays(1);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(tue, tue.plusDays(1)), null, null)); // her 2 for the week
        DayOffResponse urgent = dayOffService.create(
                new CreateDayOffRequest(anna, List.of(tue.plusDays(2)), "Hospital", true)).getFirst();
        assertTrue(urgent.emergency());
        assertEquals(DayOffStatus.PENDING, urgent.status(), "still needs a manager");
        // Doesn't use up the limit either: Ben's emergency on Anna's day is allowed too.
        assertTrue(dayOffService.create(new CreateDayOffRequest(ben, List.of(tue), null, true)).getFirst().emergency());
        // A normal one for Anna that week is still over the limit.
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(tue.plusDays(4)), null, null)));
        assertTrue(scheduleService.dayOffImpact(urgent.id()).emergency());
    }

    @Test
    void impactWorksBeforeTheWeekIsScheduled() {
        Long request = dayOffService.create(new CreateDayOffRequest(cara, List.of(monday.plusDays(4)), null, null)).getFirst().id();
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
