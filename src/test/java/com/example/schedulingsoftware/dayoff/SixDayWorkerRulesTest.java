package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Someone working 6 days a week gets 1 day off: never 2 without an emergency, and never on a day stores are short. */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.reserve-days-off=true", "alliancetel.scheduling.one-emergency-per-month=true" })
@Transactional
class SixDayWorkerRulesTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    DayOffReservations reservations;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    WeekRequests weekRequests;
    @Autowired
    RequestPortal portal;

    Long area;
    Long north;
    Long south;
    Long anna;
    Long ben;
    LocalDate week;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = DayOffService.weekStart(portal.today()).plusWeeks(1);
        area = areaService.create(new AreaRequest("Six", null, true)).id();
        north = storeService.create(new StoreRequest(area, "North", "SN", null, 1, true, null, null)).id();
        south = storeService.create(new StoreRequest(area, "South", "SS", null, 1, true, null, null)).id();
        anna = person("S-1", "Anna", north);
        ben = person("S-2", "Ben", north);
        person("S-3", "Cara", south);
        person("S-4", "Dee", south);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, Long store) {
        return employeeService.create(new EmployeeRequest(code, first, "Six", code.toLowerCase() + "@t.example", null,
                Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
    }

    private List<DayOffRequest> daysOff(Long who) {
        return requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(who)
                && !r.getOffDate().isBefore(week) && !r.getOffDate().isAfter(week.plusDays(6))
                && r.getStatus() != DayOffStatus.REJECTED).toList();
    }

    @Test
    void beforePublishingAnotherDaySwapsTheRegularDay() {
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null));
        assertEquals(List.of(week.plusDays(2)), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList());
    }

    @Test
    void onAPublishedWeekAnotherDaySwapsWithTheRegularDay() {
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        scheduleService.publish(scheduleService.generate(area, week, true).id());
        assertEquals(1, daysOff(anna).size(), "the published schedule gives Monday");

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var asked = dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null));
        assertEquals(DayOffStatus.APPROVED, asked.getFirst().status(), "a normal request, given (not an emergency)");
        assertTrue(!asked.getFirst().emergency());
        assertEquals(List.of(week.plusDays(2)), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList(),
                "Monday swapped for Wednesday");
        TestUsers.signInAsAdmin();
        var row = scheduleService.find(area, week).orElseThrow().rows().stream()
                .filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals("WORK", row.days().get(0).type().name(), "works Monday");
        assertEquals("REQUESTED_OFF", row.days().get(2).type().name(), "off Wednesday");
        // A second day is still not a normal request.
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(4)), null, null)));
    }

    @Test
    void employeesSeeTheirSystemDayOnlyOnceTheWeekIsPublished() {
        reservations.reserveWeek(area, week);
        DayOffRequest held = daysOff(anna).getFirst();
        assertTrue(held.isReserved() && !held.isRegular());
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertTrue(dayOffService.search(null, anna, null, week, week.plusDays(6)).isEmpty(), "still planning");
        TestUsers.signInAsAdmin();
        scheduleService.publish(scheduleService.generate(area, week, true).id());
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertEquals(List.of(held.getOffDate()), dayOffService.search(null, anna, null, week, week.plusDays(6)).stream()
                .map(DayOffDtos.DayOffResponse::date).toList(), "final once published");
        assertTrue(dayOffService.window().publishedWeeks().contains(week));
    }

    @Test
    void extraDaysFromBeforeTheFixMoveToEmergencyRequests() {
        // As the old version did: Monday on the published schedule, then Wednesday approved on top.
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        Long scheduleId = scheduleService.generate(area, week, true).id();
        scheduleService.publish(scheduleId);
        DayOffRequest extra = new DayOffRequest();
        extra.setEmployee(employeeService.getEmployee(anna));
        extra.setOffDate(week.plusDays(2));
        extra.setStatus(DayOffStatus.APPROVED);
        extra.setReviewNote(DayOffService.AUTO_NOTE);
        requests.saveAndFlush(extra);
        scheduleService.applyApprovedDayOff(employeeService.getEmployee(anna), week.plusDays(2));
        // A day a manager gave on purpose doesn't count.
        DayOffRequest given = new DayOffRequest();
        given.setEmployee(employeeService.getEmployee(ben));
        given.setOffDate(week.plusDays(5));
        given.setStatus(DayOffStatus.APPROVED);
        given.setReviewNote("Given by Mia Manager.");
        requests.saveAndFlush(given);

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> dayOffService.extraDaysOff());
        TestUsers.signInAsAdmin();
        var found = dayOffService.extraDaysOff().stream().filter(x -> x.employeeId().equals(anna)).toList();
        assertEquals(1, found.size());
        assertEquals(week, found.getFirst().kept().getFirst().date(), "the scheduled Monday stays");
        assertEquals(week.plusDays(2), found.getFirst().extra().getFirst().date());
        assertTrue(dayOffService.extraDaysOff().stream().noneMatch(x -> x.employeeId().equals(ben)));

        var moved = dayOffService.moveExtraDaysToEmergency();
        assertTrue(moved.days() >= 1);
        DayOffRequest now = requests.findById(extra.getId()).orElseThrow();
        assertTrue(now.isEmergency());
        assertEquals(DayOffStatus.PENDING, now.getStatus());
        var row = scheduleService.find(area, week).orElseThrow().rows().stream()
                .filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals("WORK", row.days().get(2).type().name(), "back at work until a manager decides");
        assertTrue(dayOffService.extraDaysOff().stream().noneMatch(x -> x.employeeId().equals(anna)), "nothing left");
        // The manager approves it after all: off again.
        dayOffService.approve(now.getId(), "OK");
        row = scheduleService.find(area, week).orElseThrow().rows().stream()
                .filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals("REQUESTED_OFF", row.days().get(2).type().name());
    }

    @Test
    void anExtraDayOnlyPlannedByTheSystemIsDropped() {
        reservations.reserveWeek(area, week);
        DayOffRequest planned = daysOff(anna).getFirst();
        assertTrue(planned.isReserved() && planned.getStatus() == DayOffStatus.PENDING);
        LocalDate other = planned.getOffDate().equals(week.plusDays(4)) ? week.plusDays(5) : week.plusDays(4);
        DayOffRequest asked = new DayOffRequest();
        asked.setEmployee(employeeService.getEmployee(anna));
        asked.setOffDate(other);
        asked.setStatus(DayOffStatus.APPROVED);
        asked.setReviewNote(DayOffService.AUTO_NOTE);
        requests.saveAndFlush(asked);
        dayOffService.moveExtraDaysToEmergency();
        assertEquals(List.of(other), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList());
        assertTrue(daysOff(anna).stream().noneMatch(DayOffRequest::isEmergency), "nothing for a manager to decide");
    }

    /** Safwan's week: he asked for Monday (still waiting) while the system held Saturday for him until then. */
    private DayOffRequest[] safwansWeek() {
        reservations.reserveWeek(area, week);
        DayOffRequest placeholder = daysOff(anna).getFirst();
        assertTrue(placeholder.isReserved() && placeholder.getStatus() == DayOffStatus.PENDING);
        LocalDate mine = placeholder.getOffDate().equals(week) ? week.plusDays(1) : week;
        DayOffRequest asked = new DayOffRequest();
        asked.setEmployee(employeeService.getEmployee(anna));
        asked.setOffDate(mine);
        asked.setStatus(DayOffStatus.PENDING);
        requests.saveAndFlush(asked);
        return new DayOffRequest[] { asked, placeholder };
    }

    @Test
    void aWaitingDayTheyAskedForNextToASystemPlaceholderIsNotExtra() {
        safwansWeek();
        assertTrue(dayOffService.extraDaysOff().stream().noneMatch(x -> x.employeeId().equals(anna)),
                "one day asked for + the system's placeholder is not two days off");
        dayOffService.moveExtraDaysToEmergency();
        assertTrue(daysOff(anna).stream().noneMatch(DayOffRequest::isEmergency), "nothing turned into an emergency");
    }

    @Test
    void approvingTheirDayOnAPublishedWeekReleasesThePlaceholder() {
        DayOffRequest[] days = safwansWeek();
        scheduleService.publish(scheduleService.generate(area, week, true).id());
        dayOffService.approve(days[0].getId(), "OK");
        assertEquals(List.of(days[0].getOffDate()), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList(),
                "only the day they asked for");
        var row = scheduleService.find(area, week).orElseThrow().rows().stream()
                .filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals(1, row.days().stream().filter(d -> !d.type().name().equals("WORK")).count(), "one day off");
    }

    @Test
    void wronglyMovedDaysGoBackToNormalRequests() {
        DayOffRequest[] days = safwansWeek();
        // What the first version of the tool did to Safwan's Monday.
        DayOffRequest asked = days[0];
        asked.setEmergency(true);
        asked.setReason("Extra day off");
        asked.setReviewNote(DayOffService.MOVED_NOTE + "System Admin: Anna already had 1 day off that week.");
        requests.saveAndFlush(asked);

        dayOffService.undoMistakenEmergencyMoves();
        DayOffRequest back = requests.findById(asked.getId()).orElseThrow();
        assertTrue(!back.isEmergency());
        assertEquals(DayOffStatus.PENDING, back.getStatus());
        assertNull(back.getReason());
        assertNull(back.getReviewNote());
        assertTrue(dayOffService.extraDaysOff().stream().noneMatch(x -> x.employeeId().equals(anna)));
    }

    @Test
    void someoneWhoAskedForADayHasNoOtherDayHeld() {
        DayOffRequest[] days = safwansWeek();
        // The next reservation run (hourly, or at start-up) drops the placeholder: Safwan asked for Monday.
        reservations.reserveWeek(area, week);
        assertEquals(List.of(days[0].getOffDate()), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList());
        // If his request is turned down, the system holds a day for him again.
        dayOffService.reject(days[0].getId(), "Busy");
        assertEquals(1, daysOff(anna).size());
        assertTrue(daysOff(anna).getFirst().isReserved(), "a day held for him again");
    }

    /** Surror's week: the system holds a day for Anna; Ben (same store) takes another day, so Anna can only ask it as an emergency. */
    private LocalDate[] surrorsWeek() {
        reservations.reserveWeek(area, week);
        LocalDate held = daysOff(anna).getFirst().getOffDate();
        assertTrue(daysOff(anna).getFirst().isReserved());
        LocalDate bens = daysOff(ben).isEmpty() ? null : daysOff(ben).getFirst().getOffDate();
        LocalDate wanted = week.plusDays(6).equals(held) || week.plusDays(6).equals(bens) ? week.plusDays(5) : week.plusDays(6);
        TestUsers.signInAs(ben, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wanted), null, null));
        return new LocalDate[] { held, wanted };
    }

    @Test
    void anEmergencyReplacesTheDayTheSystemPicked() {
        LocalDate[] d = surrorsWeek();
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(d[1]), "Family", true)).getFirst();
        assertEquals(List.of(d[1]), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList(),
                "the system's day is gone: Anna works it");
        // The hourly run doesn't hold another day while the emergency waits.
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        assertEquals(1, daysOff(anna).size());
        // Turned down: a day is held for her again.
        dayOffService.reject(urgent.id(), "Sorry");
        assertEquals(1, daysOff(anna).size());
        assertTrue(daysOff(anna).getFirst().isReserved());
    }

    @Test
    void onAPublishedWeekTheSystemDayGoesWhenTheEmergencyIsApproved() {
        LocalDate[] d = surrorsWeek();
        TestUsers.signInAsAdmin();
        scheduleService.publish(scheduleService.generate(area, week, true).id());
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(d[1]), "Family", true)).getFirst();
        assertEquals(2, daysOff(anna).size(), "published: the system's day stays until a decision");
        TestUsers.signInAsAdmin();
        dayOffService.approve(urgent.id(), "OK");
        assertEquals(List.of(d[1]), daysOff(anna).stream().map(DayOffRequest::getOffDate).toList());
        var row = scheduleService.find(area, week).orElseThrow().rows().stream()
                .filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals(1, row.days().stream().filter(x -> !x.type().name().equals("WORK")).count(),
                "one day off on the schedule: the emergency");
    }

    @Test
    void theSystemDoesNotGiveADayOffWhereTheStoresAreShort() {
        // North and South each need 1. With both North people's regular day on Monday, North is short that day.
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        employeeService.setRegularDaysOff(ben, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        // Cara and Dee get days the system picks: never Monday (that would leave nobody spare for North's gap).
        var all = requests.findAll().stream().filter(r -> !r.getOffDate().isBefore(week)
                && r.getEmployee().getArea().getId().equals(area) && !r.isRegular()).toList();
        assertEquals(2, all.size());
        assertTrue(all.stream().noneMatch(r -> r.getOffDate().equals(week)), "nobody else off on the short Monday");
    }

    @Test
    void aSystemDayLeftOnAShortDayMoves() {
        // Anna's regular day is Monday. As the old rule could, the system also gave Ben (same store) Monday: North
        // has nobody that day, and Cara and Dee are needed at South.
        storeService.update(south, new StoreRequest(area, "South", "SS", null, 2, true, null, null));
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        DayOffRequest wrong = new DayOffRequest();
        wrong.setEmployee(employeeService.getEmployee(ben));
        wrong.setOffDate(week);
        wrong.setReason(DayOffReservations.RESERVED_REASON);
        wrong.setReserved(true);
        wrong.setStatus(DayOffStatus.PENDING);
        requests.saveAndFlush(wrong);

        reservations.reserveWeek(area, week);
        assertEquals(1, daysOff(ben).size());
        assertTrue(daysOff(ben).getFirst().getOffDate().isAfter(week), "Ben's day moved off the short Monday");
        assertEquals(week, daysOff(anna).getFirst().getOffDate(), "Anna's regular day stays");
    }

    @Test
    void anEmergencyShowsTheirOtherDaysThatWeek() {
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        // Monday is theirs (regular); Thursday is used by Ben at the same store, so it can only be an emergency.
        TestUsers.signInAs(ben, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(ben, List.of(week.plusDays(3)), null, null));
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(3)), "Family", true));
        // An employee doesn't get the week view.
        var own = dayOffService.search(null, anna, null, week, week.plusDays(6)).stream()
                .filter(DayOffDtos.DayOffResponse::emergency).findFirst().orElseThrow();
        assertNull(own.weekDaysOff());

        TestUsers.signInAsAdmin();
        var seen = dayOffService.page(area, null, DayOffStatus.PENDING, null, null, null, null, null, true, 0, 50)
                .content().stream().filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow();
        assertEquals(1, seen.weekDaysOff().size());
        assertEquals(week, seen.weekDaysOff().getFirst().date());
        assertEquals("REGULAR", seen.weekDaysOff().getFirst().kind());
    }
}
