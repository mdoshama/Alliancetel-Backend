package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Drafts built by hand next to the week's schedule: an empty one, and one with only the days off filled in. The
 * system can fill what's left by the usual rules, and publishing a draft makes it the week's schedule.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.require-requests-before-generate=false" })
@Transactional
class HandBuiltDraftsTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    RequestPortal portal;

    Long area;
    Long north;
    Long south;
    Long anna;
    Long ben;
    Long cara;
    Long dev;
    LocalDate week;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = DayOffService.weekStart(portal.today()).plusWeeks(1);
        area = areaService.create(new AreaRequest("Drafts Area", null, true)).id();
        north = storeService.create(new StoreRequest(area, "North", "NTH", null, 1, true, null, null)).id();
        south = storeService.create(new StoreRequest(area, "South", "STH", null, 1, true, null, null)).id();
        anna = person("DR-1", "Anna", north, true);
        ben = person("DR-2", "Ben", north, false);
        cara = person("DR-3", "Cara", south, true);
        dev = person("DR-4", "Dev", south, false);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, Long store, boolean primary) {
        return employeeService.create(new EmployeeRequest(code, first, "Draft", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, primary)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long id) {
        return s.rows().stream().filter(r -> r.employeeId().equals(id)).findFirst().orElseThrow();
    }

    private static long count(ScheduleResponse s, DayType type) {
        return s.rows().stream().flatMap(r -> r.days().stream()).filter(c -> c.type() == type).count();
    }

    @Test
    void theEmptyDraftIsTotallyEmptyAndTheManagerDecidesTheDaysOff() {
        LocalDate wed = week.plusDays(2);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null));
        TestUsers.signInAsAdmin();

        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        assertEquals(ScheduleKind.EMPTY, draft.kind());
        assertEquals(28, draft.blankDays(), "4 people x 7 days, all empty");
        DayCell annaWed = row(draft, anna).days().get(2);
        assertEquals(DayType.UNSET, annaWed.type());
        assertTrue(annaWed.approvedRequestId() != null, "her approved day off shows as a hint");

        // The manager decides: Ben off on Monday, Ben at South on Tuesday.
        DayCell benMon = row(draft, ben).days().getFirst();
        DayCell benTue = row(draft, ben).days().get(1);
        scheduleService.updateEntry(draft.id(), benMon.entryId(), DayType.OFF, null);
        ScheduleResponse placed = scheduleService.updateEntry(draft.id(), benTue.entryId(), DayType.WORK, south);
        assertEquals(DayType.OFF, row(placed, ben).days().getFirst().type());
        assertEquals(south, row(placed, ben).days().get(1).storeId());
        assertEquals(26, placed.blankDays());
        assertEquals(27, scheduleService.updateEntry(draft.id(), benMon.entryId(), DayType.UNSET, null).blankDays());

        // The week's main schedule doesn't exist yet: the draft is separate.
        assertTrue(scheduleService.find(area, week).isEmpty());
        assertEquals(1, scheduleService.drafts(area, week).size());
    }

    @Test
    void theSystemFillsTheRestOfTheEmptyDraftAroundTheManagersDaysOff() {
        LocalDate wed = week.plusDays(2);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null));
        TestUsers.signInAsAdmin();
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        // The manager gives Ben Friday off; everything else is left to the system.
        scheduleService.updateEntry(draft.id(), row(draft, ben).days().get(4).entryId(), DayType.OFF, null);

        ScheduleResponse filled = scheduleService.fillBlanks(draft.id());
        assertEquals(0, filled.blankDays());
        assertEquals(DayType.OFF, row(filled, ben).days().get(4).type(), "the manager's day off stays");
        assertEquals(DayType.REQUESTED_OFF, row(filled, anna).days().get(2).type(), "a blank approved day stays off");
        for (EmployeeRow r : filled.rows()) {
            assertEquals(6, r.scheduledWorkDays(), r.employeeName() + " works their 6 days");
        }
        assertTrue(filled.coverageGaps().isEmpty(), "every store covered: " + filled.coverageGaps());
    }

    @Test
    void workingOnAnApprovedDayOffInTheEmptyDraftTurnsItDownWhenPublished() {
        LocalDate wed = week.plusDays(2);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null)).getFirst().id();
        TestUsers.signInAsAdmin();
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        scheduleService.updateEntry(draft.id(), row(draft, anna).days().get(2).entryId(), DayType.WORK, north);
        scheduleService.updateEntry(draft.id(), row(draft, anna).days().get(5).entryId(), DayType.OFF, null);

        ScheduleResponse published = scheduleService.publish(scheduleService.fillBlanks(draft.id()).id());
        assertEquals(DayType.WORK, row(published, anna).days().get(2).type(), "the manager's decision is published");
        assertEquals(com.example.schedulingsoftware.dayoff.DayOffStatus.REJECTED,
                dayOffService.page(area, anna, null, wed, wed, null, 0, 10).content().getFirst().status());
        assertTrue(request != null);
    }

    @Test
    void theDaysOffDraftStartsWithRequestsAndRegularDaysThenTheSystemFillsTheRest() {
        employeeService.setRegularDaysOff(ben, List.of(java.time.DayOfWeek.TUESDAY));
        LocalDate thu = week.plusDays(3);
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(thu), null, null));
        TestUsers.signInAsAdmin();

        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        assertEquals(DayType.OFF, row(draft, ben).days().get(1).type(), "Ben's regular Tuesday");
        assertEquals(DayType.REQUESTED_OFF, row(draft, cara).days().get(3).type(), "Cara's request");
        assertEquals(26, draft.blankDays());

        // A manager places Anna at South on Monday by hand; the system keeps it.
        DayCell annaMon = row(draft, anna).days().getFirst();
        scheduleService.updateEntry(draft.id(), annaMon.entryId(), DayType.WORK, south);

        ScheduleResponse filled = scheduleService.fillBlanks(draft.id());
        assertEquals(0, filled.blankDays());
        for (EmployeeRow r : filled.rows()) {
            assertEquals(6, r.scheduledWorkDays(), r.employeeName() + " works their 6 days");
        }
        assertEquals(south, row(filled, anna).days().getFirst().storeId(), "the hand-placed day stays");
        assertEquals(DayType.OFF, row(filled, ben).days().get(1).type());
        assertTrue(filled.coverageGaps().isEmpty(), "every store covered: " + filled.coverageGaps());
        assertEquals(4, count(filled, DayType.OFF) + count(filled, DayType.REQUESTED_OFF));
    }

    @Test
    void publishingADraftMakesItTheWeeksSchedule() {
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        BusinessRuleException blanks = assertThrows(BusinessRuleException.class,
                () -> scheduleService.publish(draft.id()));
        assertTrue(blanks.getMessage().contains("still empty"), blanks.getMessage());

        ScheduleResponse filled = scheduleService.fillBlanks(draft.id());
        ScheduleResponse published = scheduleService.publish(filled.id());
        assertEquals(ScheduleKind.MAIN, published.kind());
        assertEquals(ScheduleStatus.PUBLISHED, published.status());
        for (EmployeeRow r : filled.rows()) {
            EmployeeRow p = row(published, r.employeeId());
            for (int i = 0; i < 7; i++) {
                assertEquals(r.days().get(i).type(), p.days().get(i).type());
                assertEquals(r.days().get(i).storeId(), p.days().get(i).storeId());
            }
        }
        // Staff see the published week; the draft is still there.
        assertEquals(ScheduleStatus.PUBLISHED, scheduleService.find(area, week).orElseThrow().status());
        assertEquals(2, scheduleService.drafts(area, week).size());
    }

    @Test
    void theMainScheduleNeverHasBlankDays() {
        ScheduleResponse main = scheduleService.generate(area, week, true);
        DayCell any = main.rows().getFirst().days().getFirst();
        assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(main.id(), any.entryId(), DayType.UNSET, null));
    }
}
