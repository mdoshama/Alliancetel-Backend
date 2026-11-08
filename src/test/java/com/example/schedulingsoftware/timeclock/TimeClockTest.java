package com.example.schedulingsoftware.timeclock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Back office clocks store staff in (required) and out (optional); managers can look. */
@SpringBootTest
@Transactional
class TimeClockTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    TimeClockService timeClock;

    LocalDate today = LocalDate.now(ZoneId.of("America/Los_Angeles"));
    Long area;
    Long store;
    Long ana;
    Long backOffice;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Clock Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Clock Store", "CLK", null, 1, true, null, null)).id();
        ana = employeeService.create(new EmployeeRequest("CK-1", "Ana", "Clock", "ck-1@test.example", null,
                Role.EMPLOYEE, area, store, 7, true, null)).employee().id();
        backOffice = employeeService.create(new EmployeeRequest("BO-1", "Bo", "Office", "bo-1@test.example", null,
                Role.BACK_OFFICE, null, null, 5, true, null)).employee().id();
        var week = scheduleService.generate(area, today);
        scheduleService.publish(week.id());
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private TimeClockDtos.Row anaRow() {
        return timeClock.day(area, today).rows().stream().filter(r -> r.employeeId().equals(ana)).findFirst()
                .orElseThrow();
    }

    @Test
    void backOfficeClocksInAndOptionallyOut() {
        TestUsers.signInAs(backOffice, Role.BACK_OFFICE, null);
        var day = timeClock.day(area, today);
        assertTrue(day.canEdit());
        assertTrue(anaRow().scheduled(), "Ana works every day, so she's on today's sheet");
        assertEquals(null, anaRow().clockIn());

        var in = timeClock.clockIn(new TimeClockDtos.ClockInRequest(ana, today, LocalTime.of(0, 0), null, "Seen on camera"));
        assertEquals(LocalTime.MIDNIGHT, in.clockIn());
        assertEquals("Clock Store", in.storeName(), "the store she's scheduled at");
        assertEquals("Test User", in.clockedInBy());
        assertThrows(BusinessRuleException.class, () -> timeClock.clockIn(
                new TimeClockDtos.ClockInRequest(ana, today, LocalTime.of(0, 0), null, null)), "only once a day");

        // Clocking out is optional; when done, hours are worked out.
        assertEquals(null, anaRow().clockOut());
        var out = timeClock.clockOut(in.entryId(), LocalTime.of(0, 30));
        assertEquals(0.5, out.hoursWorked());
        assertFalse(anaRow().missing());
    }

    @Test
    void notInTheFutureAndOnlyBackOfficeOrAdminsEdit() {
        TestUsers.signInAs(backOffice, Role.BACK_OFFICE, null);
        assertThrows(BusinessRuleException.class, () -> timeClock.clockIn(
                new TimeClockDtos.ClockInRequest(ana, today.plusDays(1), LocalTime.NOON, null, null)));

        TestUsers.signInAs(999L, Role.MANAGER, area);
        assertFalse(timeClock.day(null, today).canEdit());
        assertNotNull(anaRow());
        assertThrows(AccessDeniedException.class, () -> timeClock.clockIn(
                new TimeClockDtos.ClockInRequest(ana, today, LocalTime.MIDNIGHT, null, null)));
    }

    @Test
    void backOfficeOnlyReachesTheTimeClock() {
        assertTrue(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("GET", "/api/time-clock"));
        assertTrue(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("POST", "/api/time-clock/clock-in"));
        assertTrue(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("GET", "/api/stores"));
        assertFalse(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("GET", "/api/employees"));
        assertFalse(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("POST", "/api/day-off-requests"));
        assertFalse(com.example.schedulingsoftware.auth.BackOfficeFilterAccess.allowed("POST", "/api/stores"));
        assertTrue(List.of(Role.values()).contains(Role.BACK_OFFICE));
    }
}
