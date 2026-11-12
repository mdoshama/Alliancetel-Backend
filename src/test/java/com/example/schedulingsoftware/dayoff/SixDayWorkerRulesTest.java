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
    void onAPublishedWeekAnotherDayNeedsAnEmergency() {
        employeeService.setRegularDaysOff(anna, List.of(DayOfWeek.MONDAY));
        reservations.reserveWeek(area, week);
        scheduleService.publish(scheduleService.generate(area, week, true).id());
        assertEquals(1, daysOff(anna).size(), "the published schedule gives Monday");

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null)));
        assertTrue(e.getMessage().contains("already published") && e.getMessage().contains("emergency"), e.getMessage());
        assertEquals(1, daysOff(anna).size(), "still only Monday");
        // As an emergency it goes to a manager (it isn't given automatically).
        var urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), "Sick", true));
        assertEquals(DayOffStatus.PENDING, urgent.getFirst().status());
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
