package com.example.schedulingsoftware.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.DayHours;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreDtos.StoreResponse;
import com.example.schedulingsoftware.support.TestUsers;

/** A store can need fewer (or more) people on some days than its usual minimum, e.g. 3 all week but 2 on Sunday. */
@SpringBootTest
@Transactional
class DailyMinimumTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    StoreRepository storeRepository;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    ScheduleService scheduleService;

    Long area;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Minimum Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private List<DayHours> week(Integer sundayMin, Integer saturdayMin) {
        LocalTime ten = LocalTime.of(10, 0);
        LocalTime eight = LocalTime.of(20, 0);
        return List.of(
                new DayHours(DayOfWeek.MONDAY, ten, eight, false),
                new DayHours(DayOfWeek.TUESDAY, ten, eight, false),
                new DayHours(DayOfWeek.WEDNESDAY, ten, eight, false),
                new DayHours(DayOfWeek.THURSDAY, ten, eight, false),
                new DayHours(DayOfWeek.FRIDAY, ten, eight, false),
                new DayHours(DayOfWeek.SATURDAY, ten, eight, false, saturdayMin),
                new DayHours(DayOfWeek.SUNDAY, LocalTime.of(11, 0), LocalTime.of(19, 0), false, sundayMin));
    }

    @Test
    void aDayCanHaveItsOwnMinimum() {
        StoreResponse s = storeService.create(new StoreRequest(area, "Main", "MN", null, 3, true, week(2, null), null));
        assertEquals(2, s.hours().get(6).minStaff(), "Sunday");
        assertNull(s.hours().get(0).minStaff(), "Monday uses the usual minimum");

        Store store = storeRepository.findById(s.id()).orElseThrow();
        assertEquals(3, store.minStaffOn(monday));
        assertEquals(2, store.minStaffOn(monday.plusDays(6)));
    }

    @Test
    void theScheduleFlagsEachDayAgainstThatDaysMinimum() {
        Long store = storeService.create(new StoreRequest(area, "Main", "MN", null, 3, true, week(2, 4), null)).id();
        for (int i = 1; i <= 2; i++) {
            employeeService.create(new EmployeeRequest("DM-" + i, "Person" + i, "Min", "dm-" + i + "@test.example", null,
                    Role.EMPLOYEE, area, store, 7, true, null));
        }
        ScheduleResponse s = scheduleService.generate(area, monday);
        // Two people every day: short of 3 on weekdays, short of 4 on Saturday, enough for Sunday's 2.
        assertTrue(s.coverageGaps().stream().anyMatch(g -> g.date().equals(monday) && g.required() == 3));
        assertTrue(s.coverageGaps().stream().anyMatch(g -> g.date().equals(monday.plusDays(5)) && g.required() == 4));
        assertTrue(s.coverageGaps().stream().noneMatch(g -> g.date().equals(monday.plusDays(6))), "Sunday is covered");
        assertEquals(2, s.storeCoverage().getFirst().dailyHours().get(6).minStaff());
    }

    @Test
    void aDaysMinimumMustBeSensible() {
        assertThrows(BusinessRuleException.class, () -> storeService.create(
                new StoreRequest(area, "Main", "MN", null, 3, true, week(-1, null), null)));
    }
}
