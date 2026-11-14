package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.support.TestUsers;

/** A Market Manager manages every area an admin assigns them (and their home area), and no other. */
@SpringBootTest
@Transactional
class MarketManagerTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    ScheduleService scheduleService;

    Long sanDiego;
    Long losAngeles;
    Long sanFrancisco;
    Long mm;
    Long laEmployee;
    Long sfEmployee;
    LocalDate nextMonday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        sanDiego = areaService.create(new AreaRequest("MM SD", null, true)).id();
        losAngeles = areaService.create(new AreaRequest("MM LA", null, true)).id();
        sanFrancisco = areaService.create(new AreaRequest("MM SF", null, true)).id();
        mm = create("MM-1", Role.MARKET_MANAGER, sanDiego);
        laEmployee = create("MM-2", Role.EMPLOYEE, losAngeles);
        sfEmployee = create("MM-3", Role.EMPLOYEE, sanFrancisco);
        create("MM-4", Role.EMPLOYEE, sanDiego);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long create(String code, Role role, Long areaId) {
        return employeeService.create(new EmployeeRequest(code, "Test", code, code.toLowerCase() + "@test.example",
                null, role, areaId, null, 6, true, null)).employee().id();
    }

    @Test
    void adminAssignsAreasAndTheyApplyAtSignIn() {
        var saved = employeeService.setManagedAreas(mm, List.of(losAngeles, sanDiego));
        assertEquals(List.of(losAngeles), saved.managedAreaIds(), "home area isn't listed twice");
        assertEquals(Set.of(sanDiego, losAngeles), employeeRepository.managedAreaIdsOf(mm, Role.MARKET_MANAGER));
    }

    @Test
    void managesOnlyTheirAreas() {
        employeeService.setManagedAreas(mm, List.of(losAngeles));
        TestUsers.signInAsMarketManager(mm, sanDiego, Set.of(losAngeles));

        assertEquals(Set.of(sanDiego, losAngeles), Set.copyOf(areaService.findAll().stream().map(a -> a.id()).toList()));
        assertTrue(employeeService.search(losAngeles, null, null, null).stream()
                .allMatch(e -> losAngeles.equals(e.areaId())));
        assertThrows(AccessDeniedException.class, () -> employeeService.search(sanFrancisco, null, null, null));
        assertNotNull(scheduleService.generate(losAngeles, nextMonday));
        assertNotNull(scheduleService.generate(sanDiego, nextMonday));
        assertThrows(AccessDeniedException.class, () -> scheduleService.generate(sanFrancisco, nextMonday));
    }

    @Test
    void decidesRequestsInTheirAreasOnly() {
        employeeService.setManagedAreas(mm, List.of(losAngeles));
        TestUsers.signInAs(laEmployee, Role.EMPLOYEE, losAngeles);
        dayOffService.create(new CreateDayOffRequest(laEmployee, List.of(nextMonday.plusDays(1)), null, null));
        Long la = dayOffService.create(new CreateDayOffRequest(laEmployee, List.of(nextMonday), "Urgent", true))
                .getFirst().id();
        TestUsers.signInAs(sfEmployee, Role.EMPLOYEE, sanFrancisco);
        dayOffService.create(new CreateDayOffRequest(sfEmployee, List.of(nextMonday.plusDays(1)), null, null));
        Long sf = dayOffService.create(new CreateDayOffRequest(sfEmployee, List.of(nextMonday), "Urgent", true))
                .getFirst().id();

        TestUsers.signInAsMarketManager(mm, sanDiego, Set.of(losAngeles));
        assertEquals(DayOffStatus.APPROVED, dayOffService.approve(la, null).status());
        assertThrows(AccessDeniedException.class, () -> dayOffService.approve(sf, null));
    }

    @Test
    void onlyAdminsAssignAreasAndOnlyToMarketManagers() {
        assertThrows(BusinessRuleException.class, () -> employeeService.setManagedAreas(laEmployee, List.of(sanDiego)));
        TestUsers.signInAsMarketManager(mm, sanDiego, Set.of());
        assertThrows(AccessDeniedException.class, () -> employeeService.setManagedAreas(mm, List.of(sanFrancisco)));
    }
}
