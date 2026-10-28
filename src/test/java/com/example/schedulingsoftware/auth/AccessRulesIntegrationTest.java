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
                dayOffService.create(new CreateDayOffRequest(laEmployee, List.of(nextMonday), null)));
        Long requestId = dayOffService.create(new CreateDayOffRequest(sdEmployee, List.of(nextMonday), null))
                .getFirst().id();
        assertEquals(1, dayOffService.search(null, null, null, null, null).size());

        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        dayOffService.approve(requestId, null);
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
    void managerCannotApproveOwnRequest() {
        TestUsers.signInAs(sdManager, Role.MANAGER, sanDiego);
        Long own = dayOffService.create(new CreateDayOffRequest(sdManager, List.of(nextMonday), null)).getFirst().id();
        assertThrows(AccessDeniedException.class, () -> dayOffService.approve(own, null));

        TestUsers.signInAsAdmin();
        dayOffService.approve(own, null);
    }

    @Test
    void weakPasswordsAreRejected() {
        assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate("short1"));
        assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate("allletters"));
        PasswordPolicy.validate("Welcome123");
        String generated = PasswordPolicy.generateTemporary();
        PasswordPolicy.validate(generated);
    }

    private CredentialsResponse create(String code, Role role, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, areaId, null, 6, true));
    }
}
