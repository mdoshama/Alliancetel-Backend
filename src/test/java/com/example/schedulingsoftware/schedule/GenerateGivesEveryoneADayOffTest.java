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
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.GenerateScheduleRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Generating a week never leaves anyone without their days off: regular day first, then what they asked for, and
 * the system picks the rest, even when nothing was reserved beforehand.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.reserve-days-off=false",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class GenerateGivesEveryoneADayOffTest {

    @Autowired
    ScheduleController controller;
    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    WeekRequests weekRequests;
    @Autowired
    DayOffRequestRepository requests;

    Long area;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Gen Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, int days) {
        return employee(code, first, store, days, null);
    }

    private Long employee(String code, String first, Long store, int days, String phone) {
        return employeeService.create(new EmployeeRequest(code, first, "Gen", code.toLowerCase() + "@test.example",
                phone, Role.EMPLOYEE, area, store, days, true, null)).employee().id();
    }

    @Test
    void noRequestMeansTheSystemPicksWhenTheWeekIsGenerated() {
        Long north = storeService.create(new StoreRequest(area, "North", "GN", null, 1, true, null, null)).id();
        Long south = storeService.create(new StoreRequest(area, "South", "GS", null, 1, true, null, null)).id();
        Long anna = employee("GN-1", "Anna", north, 6);
        Long ben = employee("GN-2", "Ben", north, 6, "(619) 555-0142");
        Long cara = employee("GN-3", "Cara", south, 6);
        Long dee = employee("GN-4", "Dee", south, 5);
        employeeService.setRegularDaysOff(anna, List.of(java.time.DayOfWeek.TUESDAY));
        dayOffService.create(new CreateDayOffRequest(cara, List.of(monday.plusDays(3)), null, null));

        // Before generating, Anna shows with her regular day, not as "no request yet".
        var status = weekRequests.status(area, monday);
        assertTrue(status.regular().stream().anyMatch(p -> p.employeeId().equals(anna)
                && p.dates().equals(List.of(monday.plusDays(1)))), "Anna listed with her regular day");
        assertTrue(status.missing().stream().noneMatch(p -> p.employeeId().equals(anna)));
        assertEquals(1, status.capacity().days().get(1).off(), "her Tuesday counts in the day tiles");
        // Ben hasn't picked and has no regular day: managers get his number to call him.
        var benStatus = status.missing().stream().filter(p -> p.employeeId().equals(ben)).findFirst().orElseThrow();
        assertEquals("+16195550142", benStatus.phone());

        // Ben and Dee never asked. Generating doesn't stop: it gives everyone their days.
        ScheduleResponse s = controller.generate(new GenerateScheduleRequest(area, monday));

        assertEquals(DayType.REQUESTED_OFF, row(s, anna).days().get(1).type(), "Anna: her regular Tuesday");
        assertEquals("REGULAR", row(s, anna).days().get(1).offSource());
        assertEquals(DayType.REQUESTED_OFF, row(s, cara).days().get(3).type(), "Cara: the day she asked for");
        assertEquals("REQUESTED", row(s, cara).days().get(3).offSource());
        assertEquals(1, offDays(row(s, ben)), "Ben: one day, picked by the system");
        assertTrue(row(s, ben).days().stream().filter(c -> c.type() == DayType.REQUESTED_OFF)
                .allMatch(c -> "RESERVED".equals(c.offSource())));
        assertEquals(2, offDays(row(s, dee)), "Dee works 5 days: two days off");
        // Nobody is left working every day, and the stores stay covered.
        assertTrue(s.rows().stream().allMatch(r -> r.scheduledWorkDays() == r.expectedWorkDays()));
        assertTrue(s.coverageGaps().isEmpty(), "stores covered: " + s.coverageGaps());
        assertTrue(s.requests().isEmpty(), "nothing left waiting");
    }

    @Test
    void aRequestStillWaitingFromBeforeIsSettledWhenTheWeekIsGenerated() {
        Long north = storeService.create(new StoreRequest(area, "North", "GN", null, 1, true, null, null)).id();
        Long anna = employee("GN-1", "Anna", north, 6);
        Long ben = employee("GN-2", "Ben", north, 6);
        // Sent before the system gave days by itself: still waiting, not an emergency.
        DayOffRequest old = new DayOffRequest();
        old.setEmployee(employeeService.getEmployee(ben));
        old.setOffDate(monday.plusDays(2));
        old.setReason("Travel");
        old = requests.save(old);

        ScheduleResponse s = controller.generate(new GenerateScheduleRequest(area, monday));

        assertEquals(DayOffStatus.APPROVED, requests.findById(old.getId()).orElseThrow().getStatus());
        assertEquals("REQUESTED", row(s, ben).days().get(2).offSource());
        assertEquals(1, offDays(row(s, anna)), "Anna gets a day too, not the same one");
        assertTrue(row(s, anna).days().get(2).type() != DayType.REQUESTED_OFF);
    }

    @Test
    void anEmergencyStillWaitingIsListedAloneAndDoesNotCountAsADayOff() {
        Long north = storeService.create(new StoreRequest(area, "North", "GN", null, 1, true, null, null)).id();
        employee("GN-1", "Anna", north, 6);
        Long ben = employee("GN-2", "Ben", north, 6);
        dayOffService.create(new CreateDayOffRequest(ben, List.of(monday.plusDays(4)), "Family", true, null));
        dayOffService.create(new CreateDayOffRequest(ben, List.of(monday.plusDays(1)), null, null));
        // Managers only list emergencies: the other request was given by the system.
        var emergencies = dayOffService.page(area, null, null, monday, null, null, null, null, true, 0, 25);
        assertEquals(1, emergencies.totalElements());
        assertTrue(emergencies.content().getFirst().emergency());

        ScheduleResponse s = controller.generate(new GenerateScheduleRequest(area, monday));

        assertEquals(DayType.WORK, row(s, ben).days().get(4).type(), "the emergency waits for a manager");
        assertEquals(DayType.REQUESTED_OFF, row(s, ben).days().get(1).type(), "his own pick is his day off");
        assertEquals(1, offDays(row(s, ben)));
    }

    private static long offDays(EmployeeRow row) {
        return row.days().stream().filter(c -> c.type() == DayType.REQUESTED_OFF).count();
    }

    private static EmployeeRow row(ScheduleResponse s, Long employeeId) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow();
    }
}
