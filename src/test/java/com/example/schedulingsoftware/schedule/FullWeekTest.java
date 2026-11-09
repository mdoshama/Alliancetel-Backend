package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffReservations;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** A manager lets someone work all 7 days of a week: no day off needed or reserved, and they're expected every day. */
@SpringBootTest(properties = { "alliancetel.scheduling.reserve-days-off=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class FullWeekTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    FullWeekService fullWeekService;
    @Autowired
    WeekRequests weekRequests;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    DayOffReservations reservations;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    RequestPortal portal;

    Long area;
    Long ana;
    LocalDate week;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Full Week Area", null, true)).id();
        Long store = storeService.create(new StoreRequest(area, "Only", "FW", null, 1, true, null, null)).id();
        ana = employeeService.create(new EmployeeRequest("FW-1", "Ana", "Full", "fw-1@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, false)).employee().id();
        employeeService.create(new EmployeeRequest("FW-2", "Bo", "Full", "fw-2@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, false));
        reservations.reserveWeek(area, week);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private long anasDays() {
        return requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(ana)).count();
    }

    @Test
    void workingAllSevenDaysNeedsNoDayOff() {
        assertEquals(1, anasDays(), "her usual day is reserved");
        fullWeekService.set(ana, week.plusDays(3), true);
        assertEquals(0, anasDays(), "her reserved day is released");

        var status = weekRequests.status(area, week);
        assertEquals(1, status.fullWeek().size());
        assertEquals(WeekRequests.State.FULL_WEEK, status.fullWeek().getFirst().state());
        assertTrue(status.missing().isEmpty(), "she doesn't hold up the schedule");
        assertEquals(13, status.capacity().shiftsAvailable(), "7 + 6");
        // Running reservations again doesn't give her a day back.
        reservations.reserveWeek(area, week);
        assertEquals(0, anasDays());

        var schedule = scheduleService.generate(area, week, true);
        var row = schedule.rows().stream().filter(r -> r.employeeId().equals(ana)).findFirst().orElseThrow();
        assertEquals(7, row.expectedWorkDays());
        assertEquals(7, row.scheduledWorkDays());

        // Back to her usual week: a day is reserved again.
        fullWeekService.set(ana, week, false);
        assertEquals(1, anasDays());
        assertTrue(weekRequests.status(area, week).fullWeek().isEmpty());
    }

    @Test
    void notForAPastWeek() {
        assertThrows(BusinessRuleException.class, () -> fullWeekService.set(ana, LocalDate.now().minusWeeks(2), true));
    }
}
