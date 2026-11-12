package com.example.schedulingsoftware.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.support.TestUsers;

@SpringBootTest
@Transactional
class PastWeekLockTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    PastWeekLock pastWeekLock;

    Long area;
    Long manager;
    Long lastWeekSchedule;
    LocalDate lastWeek = DayOffService.weekStart(LocalDate.now()).minusWeeks(1);
    LocalDate thisWeek = DayOffService.weekStart(LocalDate.now());

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Lock Area", null, true)).id();
        manager = person("L-1", Role.MANAGER);
        person("L-2", Role.EMPLOYEE);
        // An admin can make and publish a week that has passed.
        lastWeekSchedule = scheduleService.generate(area, lastWeek, true).id();
        scheduleService.publish(lastWeekSchedule);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void aManagerAsksAnAdminToChangeAWeekThatHasPassed() {
        TestUsers.signInAs(manager, Role.MANAGER, area);
        var status = pastWeekLock.status(area, lastWeek);
        assertTrue(status.passed());
        assertTrue(status.locked());
        var locked = assertThrows(BusinessRuleException.class, () -> scheduleService.revertToDraft(lastWeekSchedule));
        assertTrue(locked.getMessage().contains("Ask an admin"));
        assertThrows(BusinessRuleException.class, () -> scheduleService.generate(area, lastWeek, true));
        // The current week is still open.
        assertFalse(pastWeekLock.status(area, thisWeek).locked());
        assertNotNull(scheduleService.generate(area, thisWeek, true));
        assertThrows(BusinessRuleException.class, () -> pastWeekLock.request(area, thisWeek, "Fix"), "not over yet");

        assertThrows(BusinessRuleException.class, () -> pastWeekLock.request(area, lastWeek, " "), "needs a reason");
        var asked = pastWeekLock.request(area, lastWeek, "Ana swapped Tuesday with Ben");
        assertEquals(PastWeekAccess.Status.PENDING, asked.status());
        assertThrows(BusinessRuleException.class, () -> pastWeekLock.request(area, lastWeek, "Again"));
        assertThrows(AccessDeniedException.class, () -> pastWeekLock.decide(asked.id(), true, null, null),
                "only admins decide");

        TestUsers.signInAsAdmin();
        assertEquals(1, pastWeekLock.pending().stream().filter(a -> a.id().equals(asked.id())).count());
        var opened = pastWeekLock.decide(asked.id(), true, null, null);
        assertTrue(opened.open());

        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertFalse(pastWeekLock.status(area, lastWeek).locked());
        assertEquals(ScheduleStatus.DRAFT, scheduleService.revertToDraft(lastWeekSchedule).status());

        // The admin locks it again.
        TestUsers.signInAsAdmin();
        pastWeekLock.lockAgain(asked.id());
        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertThrows(BusinessRuleException.class, () -> scheduleService.publish(lastWeekSchedule));
    }

    @Test
    void aTurnedDownRequestKeepsTheWeekLocked() {
        TestUsers.signInAs(manager, Role.MANAGER, area);
        var asked = pastWeekLock.request(area, lastWeek, "Wrong store on Friday");
        TestUsers.signInAsAdmin();
        assertEquals(PastWeekAccess.Status.REJECTED, pastWeekLock.decide(asked.id(), false, null, "Leave it").status());
        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertTrue(pastWeekLock.status(area, lastWeek).locked());
        assertThrows(BusinessRuleException.class, () -> scheduleService.revertToDraft(lastWeekSchedule));
        // They can ask again.
        assertEquals(PastWeekAccess.Status.PENDING, pastWeekLock.request(area, lastWeek, "Please, it's wrong").status());
    }

    private Long person(String code, Role role) {
        return employeeService.create(new EmployeeRequest(code, "Lock", code, code.toLowerCase() + "@test.example",
                null, role, area, null, 6, true, null)).employee().id();
    }
}
