package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.support.TestUsers;

@SpringBootTest
@Transactional
class AccessRulesIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    AuthService authService;

    Long sanDiego;
    Long losAngeles;
    Long sdManager;
    Long sdEmployee;
    Long laEmployee;
    LocalDate nextMonday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        sanDiego = areaService.create(new AreaRequest("SD Test", null, true)).id();
        losAngeles = areaService.create(new AreaRequest("LA Test", null, true)).id();
        sdManager = create("M-1", Role.MANAGER, sanDiego).employee().id();
        sdEmployee = create("E-1", Role.EMPLOYEE, sanDiego).employee().id();
        laEmployee = create("E-2", Role.EMPLOYEE, losAngeles).employee().id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void newEmployeeGetsTemporaryPasswordAndMustChangeIt() {
        CredentialsResponse created = create("E-9", Role.EMPLOYEE, sanDiego);
        assertNotNull(created.temporaryPassword());
        assertTrue(created.employee().loginEnabled());

        TestUsers.signOut();
        var login = authService.login("e-9@test.example", created.temporaryPassword(), "127.0.0.1");
        assertTrue(login.user().mustChangePassword());
        assertThrows(BadCredentialsException.class, () -> authService.login("e-9@test.example", "wrong-pass1", "127.0.0.1"));
    }

    @Test
    void managerOnlySeesAndManagesOwnArea() {
        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);

        assertEquals(List.of(sanDiego), areaService.findAll().stream().map(a -> a.id()).toList());
        assertTrue(employeeService.search(null, null, null, null).stream().allMatch(e -> sanDiego.equals(e.areaId())));
        assertThrows(AccessDeniedException.class, () -> employeeService.search(losAngeles, null, null, null));
        assertThrows(AccessDeniedException.class, () -> scheduleService.generate(losAngeles, nextMonday));
        assertNotNull(scheduleService.generate(sanDiego, nextMonday));
    }

    @Test
    void employeeRequestsOnlyForSelfAndSeesOnlyPublishedSchedules() {
        TestUsers.signInAs(sdEmployee, Role.EMPLOYEE, sanDiego);
        assertThrows(AccessDeniedException.class, () ->
                dayOffService.create(new CreateDayOffRequest(laEmployee, List.of(nextMonday), null, null)));
        var given = dayOffService.create(new CreateDayOffRequest(sdEmployee, List.of(nextMonday), null, null)).getFirst();
        assertEquals(com.example.schedulingsoftware.dayoff.DayOffStatus.APPROVED, given.status(), "covered: given right away");
        assertEquals(1, dayOffService.search(null, null, null, null, null).size());

        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        var draft = scheduleService.generate(sanDiego, nextMonday);

        TestUsers.signInAs(sdEmployee, Role.EMPLOYEE, sanDiego);
        assertTrue(scheduleService.find(sanDiego, nextMonday).isEmpty(), "drafts are hidden from employees");
        assertThrows(AccessDeniedException.class, () -> scheduleService.find(losAngeles, nextMonday));

        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        scheduleService.publish(draft.id());

        TestUsers.signInAs(sdEmployee, Role.EMPLOYEE, sanDiego);
        assertTrue(scheduleService.find(sanDiego, nextMonday).isPresent());
    }

    @Test
    void managerApprovesTheirOwnDayOff() {
        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        // Their normal day first: asking for yourself, an emergency is only for a week that's full.
        dayOffService.create(new CreateDayOffRequest(sdManager, List.of(nextMonday.plusDays(1)), null, null));
        Long own = dayOffService.create(new CreateDayOffRequest(sdManager, List.of(nextMonday), "Urgent", true)).getFirst().id();
        // No admin needed: a manager decides on their own day off.
        assertEquals(com.example.schedulingsoftware.dayoff.DayOffStatus.APPROVED,
                dayOffService.approve(own, null).status());
    }

    @Test
    void managerSetsHowManyDaysTheirTeamWorks() {
        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        // 5 days: room for two regular days off.
        employeeService.setWorkDaysPerWeek(sdEmployee, 5);
        assertEquals(5, employeeService.findById(sdEmployee).workDaysPerWeek());
        employeeService.setRegularDaysOff(sdEmployee, List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY));
        // Back to 6 days: only one regular day off fits, so the later one goes.
        employeeService.setWorkDaysPerWeek(sdEmployee, 6);
        var after = employeeService.findById(sdEmployee);
        assertEquals(6, after.workDaysPerWeek());
        assertEquals(List.of(java.time.DayOfWeek.MONDAY), List.copyOf(after.regularDaysOff()));

        assertThrows(AccessDeniedException.class, () -> employeeService.setWorkDaysPerWeek(laEmployee, 5),
                "not their area");
        assertThrows(BusinessRuleException.class, () -> employeeService.setWorkDaysPerWeek(sdManager, 5),
                "not for themselves");
        assertThrows(BusinessRuleException.class, () -> employeeService.setWorkDaysPerWeek(sdEmployee, 8));
        TestUsers.signInAs(sdEmployee, Role.EMPLOYEE, sanDiego);
        assertThrows(AccessDeniedException.class, () -> employeeService.setWorkDaysPerWeek(sdEmployee, 5));
    }

    @Test
    void weakPasswordsAreRejected() {
        assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate("short1"));
        assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate("allletters"));
        PasswordPolicy.validate("Blue-Harbor-47");
        String generated = PasswordPolicy.generateTemporary();
        PasswordPolicy.validate(generated);
    }

    private CredentialsResponse create(String code, Role role, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, areaId, null, 6, true, null));
    }
}
