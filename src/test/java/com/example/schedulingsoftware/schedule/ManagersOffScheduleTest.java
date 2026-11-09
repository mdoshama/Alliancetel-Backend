package com.example.schedulingsoftware.schedule;

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
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffReservations;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;
import com.example.schedulingsoftware.timeclock.TimeClockService;

/** A manager who only manages (doesn't work shifts) is left out of everything to do with the schedule. */
@SpringBootTest(properties = { "alliancetel.scheduling.reserve-days-off=true" })
@Transactional
class ManagersOffScheduleTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    WeekRequests weekRequests;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    DayOffReservations reservations;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    RequestPortal portal;
    @Autowired
    TimeClockService timeClock;

    Long area;
    Long store;
    Long manager;
    LocalDate week;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Managers Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Main", "MG", null, 1, true, null, null)).id();
        manager = person("MG-1", "Mona", Role.MANAGER, true).id();
        person("MG-2", "Eve", Role.EMPLOYEE, null);
        person("MG-3", "Ian", Role.EMPLOYEE, null);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private EmployeeResponse person(String code, String first, Role role, Boolean onSchedule) {
        return employeeService.create(new EmployeeRequest(code, first, "Mgr", code.toLowerCase() + "@test.example",
                null, role, area, store, 6, true, false, null, null, null, null, onSchedule)).employee();
    }

    private EmployeeResponse setOnSchedule(boolean on) {
        EmployeeResponse m = employeeService.findById(manager);
        return employeeService.update(manager, new EmployeeRequest(m.employeeCode(), m.firstName(), m.lastName(),
                m.email(), null, Role.MANAGER, area, store, 6, true, false, null, null, null, null, on));
    }

    @Test
    void aManagerWhoOnlyManagesIsLeftOutOfTheSchedule() {
        reservations.reserveWeek(area, week);
        assertTrue(requests.findAll().stream().anyMatch(r -> r.getEmployee().getId().equals(manager)),
                "on the schedule: she gets a reserved day like everyone");

        assertFalse(setOnSchedule(false).onSchedule());
        assertTrue(requests.findAll().stream().noneMatch(r -> r.getEmployee().getId().equals(manager)),
                "her reserved days are dropped");
        assertEquals(2, weekRequests.status(area, week).total(), "only the two who work");
        assertEquals(12, weekRequests.capacity(area, week).shiftsAvailable());

        var schedule = scheduleService.generate(area, week);
        assertEquals(2, schedule.rows().size());
        assertTrue(schedule.rows().stream().noneMatch(r -> r.employeeId().equals(manager)));

        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(manager, List.of(week.plusDays(2)), null, null)));
        assertTrue(timeClock.day(area, portal.today()).others().stream().noneMatch(r -> r.employeeId().equals(manager)));

        // Back on the schedule: she's scheduled again.
        setOnSchedule(true);
        assertEquals(3, scheduleService.generate(area, week).rows().size());
    }

    @Test
    void employeesAlwaysWorkShifts() {
        EmployeeResponse e = person("MG-4", "Zed", Role.EMPLOYEE, false);
        assertTrue(e.onSchedule(), "only managers can be off the schedule");
    }
}
