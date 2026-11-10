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
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** How the week reads: store by store, primary then secondary; employees just see "Day off". */
@SpringBootTest(properties = "alliancetel.scheduling.reserve-days-off=false")
@Transactional
class ScheduleRowOrderTest {

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

    Long area;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Order Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, Long store, Boolean primary) {
        return employeeService.create(new EmployeeRequest(code, first, "Order", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, primary)).employee().id();
    }

    @Test
    void rowsGoStoreByStorePrimaryThenSecondaryThenFloaters() {
        Long bravo = storeService.create(new StoreRequest(area, "Bravo", "BRV", null, 1, true, null, null)).id();
        Long alpha = storeService.create(new StoreRequest(area, "Alpha", "ALP", null, 1, true, null, null)).id();
        person("OR-1", "Aaron", alpha, false);
        person("OR-2", "Zoe", alpha, true);
        person("OR-3", "Bea", bravo, true);
        person("OR-4", "Abe", bravo, false);
        person("OR-5", "Cal", null, null);

        ScheduleResponse s = scheduleService.generate(area, monday, true);

        assertEquals(List.of("Zoe Order", "Aaron Order", "Bea Order", "Abe Order", "Cal Order"),
                s.rows().stream().map(EmployeeRow::employeeName).toList(),
                "Alpha: primary, secondary; Bravo: primary, secondary; then the floater");
    }

    @Test
    void employeesSeeJustDayOffNotWhereItCameFrom() {
        Long alpha = storeService.create(new StoreRequest(area, "Alpha", "ALP", null, 1, true, null, null)).id();
        Long zoe = person("OR-2", "Zoe", alpha, true);
        person("OR-1", "Aaron", alpha, false);
        dayOffService.create(new CreateDayOffRequest(zoe, List.of(monday.plusDays(2)), null, null));
        ScheduleResponse s = scheduleService.publish(scheduleService.generate(area, monday, true).id());
        assertTrue(s.rows().stream().flatMap(r -> r.days().stream()).anyMatch(c -> c.offSource() != null),
                "managers see where each day off came from");

        TestUsers.signInAs(zoe, Role.EMPLOYEE, area);
        ScheduleResponse mine = scheduleService.find(area, monday).orElseThrow();
        assertTrue(mine.rows().stream().flatMap(r -> r.days().stream())
                .allMatch(c -> c.offSource() == null && c.request() == null), "employees just see Day off");
        assertTrue(mine.rows().stream().flatMap(r -> r.days().stream())
                .anyMatch(c -> c.type() == DayType.REQUESTED_OFF));
    }
}
