package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Once a week's schedule is generated it's the manager's: the system doesn't change it by itself, and when the
 * manager moves someone's day off, the old one (asked for, regular or given by the system) goes, so a 6-day worker
 * still has one day off.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.require-requests-before-generate=false",
        "alliancetel.scheduling.reserve-days-off=true" })
@Transactional
class ManagerOwnsTheMadeWeekTest {

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
    DayOffReservations reservations;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    RequestPortal portal;

    Long area;
    Long store;
    Long anna;
    Long ben;
    Long cara;
    LocalDate week;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = DayOffService.weekStart(portal.today()).plusWeeks(1);
        area = areaService.create(new AreaRequest("Own Week Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Own Store", "OWN", null, 1, true, null, null)).id();
        anna = person("OW-1", "Anna");
        ben = person("OW-2", "Ben");
        cara = person("OW-3", "Cara");
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first) {
        return employeeService.create(new EmployeeRequest(code, first, "Own", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long id) {
        return s.rows().stream().filter(r -> r.employeeId().equals(id)).findFirst().orElseThrow();
    }

    private static int offIndex(EmployeeRow r) {
        for (int i = 0; i < 7; i++) {
            if (r.days().get(i).type() != DayType.WORK) {
                return i;
            }
        }
        throw new AssertionError("no day off");
    }

    private long liveRequests(Long employee) {
        return requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(employee)
                && r.getStatus() != com.example.schedulingsoftware.dayoff.DayOffStatus.REJECTED).count();
    }

    @Test
    void movingADayOffOnTheScheduleDropsTheOldOne() {
        reservations.reserveWeek(area, week);
        ScheduleResponse s = scheduleService.generate(area, week, true);
        int old = offIndex(row(s, anna));
        int next = old == 6 ? 5 : old + 1;
        assertEquals(1, liveRequests(anna), "the system gave her one day");

        // The manager gives her another day off: the system's day goes back to work and its request is deleted.
        DayCell newDay = row(s, anna).days().get(next);
        ScheduleResponse after = scheduleService.updateEntry(s.id(), newDay.entryId(), DayType.OFF, null);
        assertEquals(DayType.OFF, row(after, anna).days().get(next).type());
        assertEquals(DayType.WORK, row(after, anna).days().get(old).type(), "not off on the old day too");
        assertEquals(6, row(after, anna).scheduledWorkDays(), "still one day off");
        assertEquals(1, liveRequests(anna), "only the new day is hers: the old one is gone");
    }

    @Test
    void draggingTheDayOffToAnotherDayDropsTheOldRequest() {
        reservations.reserveWeek(area, week);
        ScheduleResponse s = scheduleService.generate(area, week, true);
        int old = offIndex(row(s, ben));
        int next = old == 6 ? 5 : old + 1;
        ScheduleResponse after = scheduleService.exchange(s.id(), row(s, ben).days().get(old).entryId(),
                row(s, ben).days().get(next).entryId(), null);
        assertEquals(DayType.WORK, row(after, ben).days().get(old).type());
        assertEquals(DayType.OFF, row(after, ben).days().get(next).type());
        assertEquals(6, row(after, ben).scheduledWorkDays());
        assertEquals(1, liveRequests(ben), "only the new day is his");
    }

    @Test
    void theSystemDoesntChangeAMadeWeekByItself() {
        ScheduleResponse s = scheduleService.generate(area, week, true);
        // Reserving again (e.g. the request portal opening) leaves the made week alone.
        assertEquals(0, reservations.reserveWeek(area, week));
        // An employee can't get a day given automatically any more: it's the manager's call (or an emergency).
        LocalDate day = week.plusDays(2);
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        BusinessRuleException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(cara, List.of(day), null, null)));
        assertTrue(e.getMessage().contains("already made"), e.getMessage());
        TestUsers.signInAsAdmin();
        ScheduleResponse again = scheduleService.find(area, week).orElseThrow();
        for (EmployeeRow r : s.rows()) {
            for (int i = 0; i < 7; i++) {
                assertEquals(r.days().get(i).type(), row(again, r.employeeId()).days().get(i).type());
            }
        }
    }

    @Test
    void theManagerCanPutSomeoneToWorkOnTheirDayOff() {
        reservations.reserveWeek(area, week);
        ScheduleResponse s = scheduleService.generate(area, week, true);
        int old = offIndex(row(s, cara));
        DayCell off = row(s, cara).days().get(old);
        ScheduleResponse after = scheduleService.updateEntry(s.id(), off.entryId(), DayType.WORK, store);
        assertEquals(DayType.WORK, row(after, cara).days().get(old).type());
        assertEquals(0, liveRequests(cara), "that day-off request is deleted");
    }
}
