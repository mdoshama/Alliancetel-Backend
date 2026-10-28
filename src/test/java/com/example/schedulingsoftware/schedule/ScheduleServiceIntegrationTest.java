package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.support.TestUsers;

@SpringBootTest
@Transactional
class ScheduleServiceIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    ScheduleService scheduleService;

    @BeforeEach
    void signIn() {
        TestUsers.signInAsAdmin();
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void generatesWeekUsingApprovedRequestsAndLocksWhenPublished() {
        Long areaId = areaService.create(new AreaRequest("Test Area", null, true)).id();
        Long annaId = employee("T-1", "Anna", areaId);
        Long benId = employee("T-2", "Ben", areaId);
        employee("T-3", "Cara", areaId);

        LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);
        LocalDate tuesday = monday.plusDays(1);

        Long annaRequest = dayOffService.create(new CreateDayOffRequest(annaId, List.of(tuesday), "Doctor")).getFirst().id();
        dayOffService.approve(annaRequest, null);
        // Ben's request stays pending, so the scheduler should ignore it and report it.
        dayOffService.create(new CreateDayOffRequest(benId, List.of(monday.plusDays(3)), null));

        ScheduleResponse schedule = scheduleService.generate(areaId, monday.plusDays(4));

        assertEquals(monday, schedule.weekStart());
        assertEquals(3, schedule.rows().size());
        assertEquals(1, schedule.pendingDayOffRequests());
        EmployeeRow anna = row(schedule, annaId);
        assertEquals(DayType.REQUESTED_OFF, anna.days().get(1).type());
        schedule.rows().forEach(r -> assertEquals(6, r.scheduledWorkDays()));

        ScheduleResponse published = scheduleService.publish(schedule.id());
        assertEquals(ScheduleStatus.PUBLISHED, published.status());
        assertThrows(BusinessRuleException.class, () -> scheduleService.generate(areaId, monday));

        scheduleService.revertToDraft(schedule.id());
        ScheduleResponse regenerated = scheduleService.generate(areaId, monday);
        assertEquals(21, regenerated.rows().stream().mapToInt(r -> r.days().size()).sum());
    }

    @Test
    void rejectsMoreThanTwoDaysOffInOneWeek() {
        Long areaId = areaService.create(new AreaRequest("Limit Area", null, true)).id();
        Long id = employee("L-1", "Dana", areaId);
        LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(2);

        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(id, List.of(monday, monday.plusDays(1), monday.plusDays(2)), null)));
    }

    private Long employee(String code, String name, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, name, "Tester", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, areaId, null, 6, true)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long employeeId) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow();
    }
}
