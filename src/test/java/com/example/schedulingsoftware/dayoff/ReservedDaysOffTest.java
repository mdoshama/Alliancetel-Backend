package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Days off reserved for everyone when a week opens, swapped automatically when someone asks for another day, and the
 * admin's open / close switch for requests.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.reserve-days-off=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class ReservedDaysOffTest {

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
    RequestPortal portal;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    WeekRequests weekRequests;

    LocalDate week;
    Long area;
    Long anna;
    Long ben;
    Long cara;
    Long dee;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Reserve Area", null, true)).id();
        Long north = storeService.create(new StoreRequest(area, "North", "RN", null, 1, true, null, null)).id();
        Long south = storeService.create(new StoreRequest(area, "South", "RS", null, 1, true, null, null)).id();
        anna = employee("RV-1", "Anna", north, 6);
        ben = employee("RV-2", "Ben", north, 6);
        cara = employee("RV-3", "Cara", south, 6);
        dee = employee("RV-4", "Dee", south, 5);
        employee("RV-5", "Eli", null, 6);
        employee("RV-6", "Fay", null, 6);
        reservations.reserveWeek(area, week);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, int days) {
        return employeeService.create(new EmployeeRequest(code, first, "Reserve", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, days, true, null)).employee().id();
    }

    private List<DayOffRequest> daysOff(Long employeeId) {
        return requests.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(employeeId))
                .filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                .filter(r -> !r.getOffDate().isBefore(week) && r.getOffDate().isBefore(week.plusDays(7)))
                .toList();
    }

    @Test
    void everyoneGetsTheirUsualDaysReserved() {
        assertEquals(1, daysOff(anna).size());
        assertEquals(2, daysOff(dee).size(), "5-day workers get two days");
        assertTrue(daysOff(dee).stream().allMatch(DayOffRequest::isReserved));
        assertEquals(DayOffStatus.APPROVED, daysOff(anna).getFirst().getStatus());
        // Anna and Ben share a store: their reserved days differ.
        assertFalse(daysOff(anna).getFirst().getOffDate().equals(daysOff(ben).getFirst().getOffDate()));
        // Running again changes nothing.
        assertEquals(0, reservations.reserveWeek(area, week));
    }

    @Test
    void askingForAnotherDayWaitsForAManagerThenSwaps() {
        LocalDate reserved = daysOff(anna).getFirst().getOffDate();
        LocalDate wanted = week.datesUntil(week.plusDays(7))
                .filter(d -> !d.equals(reserved)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var result = dayOffService.create(new CreateDayOffRequest(anna, List.of(wanted), "Family", null));
        assertEquals(DayOffStatus.PENDING, result.getFirst().status(), "never approved automatically");
        assertEquals(2, daysOff(anna).size(), "her reserved day stays until a manager decides");
        assertTrue(daysOff(anna).stream().anyMatch(r -> r.isReserved() && r.getOffDate().equals(reserved)));

        TestUsers.signInAsAdmin();
        dayOffService.approve(result.getFirst().id(), null);
        List<DayOffRequest> mine = daysOff(anna);
        assertEquals(1, mine.size(), "approved: the reserved day went back to work");
        assertEquals(wanted, mine.getFirst().getOffDate());
        // Whoever else at North had that day reserved got another day; everyone still has their days.
        var status = weekRequests.status(area, week);
        assertEquals(0, status.missing().size());
        assertEquals(1, daysOff(ben).size());
        assertFalse(daysOff(ben).getFirst().getOffDate().equals(wanted));
    }

    @Test
    void aTurnedDownRequestLeavesTheReservedDay() {
        LocalDate reserved = daysOff(cara).getFirst().getOffDate();
        LocalDate other = week.datesUntil(week.plusDays(7))
                .filter(d -> !d.equals(reserved)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        Long id = dayOffService.create(new CreateDayOffRequest(cara, List.of(other), null, null)).getFirst().id();
        TestUsers.signInAsAdmin();
        dayOffService.reject(id, "Busy day");
        List<DayOffRequest> mine = daysOff(cara);
        assertEquals(1, mine.size());
        assertEquals(reserved, mine.getFirst().getOffDate());
    }

    @Test
    void askingForTheReservedDayKeepsIt() {
        LocalDate reserved = daysOff(cara).getFirst().getOffDate();
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(reserved), null, null));
        assertEquals(1, daysOff(cara).size());
        assertFalse(daysOff(cara).getFirst().isReserved());
    }

    @Test
    void adminsOpenAndCloseRequests() {
        TestUsers.signInAsAdmin();
        dayOffService.setPortal(PortalMode.CLOSED);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        BusinessRuleException closed = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null)));
        assertTrue(closed.getMessage().contains("closed right now"), closed.getMessage());

        TestUsers.signInAsAdmin();
        var w = dayOffService.setPortal(PortalMode.OPEN);
        assertTrue(w.openWeeks().contains(DayOffService.weekStart(portal.today()).plusWeeks(1)), "next week reopened");
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.setPortal(PortalMode.AUTO));
    }

    @Test
    void theWeekWaitsWhileRequestsAreOpenUnlessAnAdminClosesThem() {
        TestUsers.signInAsAdmin();
        BusinessRuleException open = assertThrows(BusinessRuleException.class,
                () -> scheduleService.generate(area, week));
        assertTrue(open.getMessage().contains("still open"), open.getMessage());
        dayOffService.setPortal(PortalMode.CLOSED);
        var schedule = scheduleService.generate(area, week);
        long annaOff = schedule.rows().stream().filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow()
                .days().stream().filter(c -> c.type() != com.example.schedulingsoftware.schedule.DayType.WORK).count();
        assertTrue(annaOff >= 1, "her reserved day is off on the schedule");
    }
}
