package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Managers open and close day-off requests for their own area and edit their employees (no role changes or
 * deactivating); a rejected day can be asked for again only as an emergency; Market Managers have no home area.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.one-emergency-per-month=true" })
@Transactional
class ManagerPortalAndRejectedTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;

    LocalDate today = LocalDate.now(ZoneId.of("America/Los_Angeles"));
    LocalDate nextMonday = DayOffService.weekStart(today).plusWeeks(1);
    Long area;
    Long otherArea;
    Long store;
    Long manager;
    Long otherManager;
    Long anna;
    Long ben;
    Long otherEmployee;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Portal Area", null, true)).id();
        otherArea = areaService.create(new AreaRequest("Portal Other", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Portal Store", "PTS", null, 1, true, null, null)).id();
        Long otherStore = storeService.create(new StoreRequest(otherArea, "Other Store", "OTS", null, 1, true, null, null)).id();
        manager = person("P-1", "Mia", Role.MANAGER, area, store);
        otherManager = person("P-2", "Omar", Role.MANAGER, otherArea, otherStore);
        anna = person("P-3", "Anna", Role.EMPLOYEE, area, store);
        ben = person("P-4", "Ben", Role.EMPLOYEE, area, store);
        otherEmployee = person("P-5", "Olga", Role.EMPLOYEE, otherArea, otherStore);
        person("P-6", "Oz", Role.EMPLOYEE, otherArea, otherStore);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, Role role, Long areaId, Long homeStore) {
        return employeeService.create(new EmployeeRequest(code, first, "Portal", code.toLowerCase() + "@test.example",
                null, role, areaId, homeStore, 6, true, null)).employee().id();
    }

    private EmployeeRequest edit(EmployeeResponse e, String firstName, Role role, Boolean active, Boolean trainee) {
        return new EmployeeRequest(e.employeeCode(), firstName, e.lastName(), e.email(), e.phone(), role, e.areaId(),
                e.homeStoreId(), e.workDaysPerWeek(), active, null, null, null, null, null, null, null, trainee);
    }

    @Test
    void aManagerOpensAndClosesRequestsForTheirAreaOnly() {
        TestUsers.signInAs(manager, Role.MANAGER, area);
        var w = dayOffService.setPortal(PortalMode.CLOSED, area);
        assertTrue(w.openWeeks().isEmpty());
        assertTrue(w.areaOwn());

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        BusinessRuleException closed = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(nextMonday.plusDays(1)), null, null)));
        assertTrue(closed.getMessage().contains("closed right now"), closed.getMessage());

        // The other area still takes requests.
        TestUsers.signInAs(otherEmployee, Role.EMPLOYEE, otherArea);
        assertEquals(1, dayOffService.create(
                new CreateDayOffRequest(otherEmployee, List.of(nextMonday.plusDays(1)), null, null)).size());

        // Not another area, and not every area at once.
        TestUsers.signInAs(otherManager, Role.MANAGER, otherArea);
        assertThrows(AccessDeniedException.class, () -> dayOffService.setPortal(PortalMode.OPEN, area));
        assertThrows(AccessDeniedException.class, () -> dayOffService.setPortal(PortalMode.OPEN, null));

        // Back to the company setting: open again.
        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertEquals(2, dayOffService.setPortal(PortalMode.AUTO, area).openWeeks().size());
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertEquals(1, dayOffService.create(
                new CreateDayOffRequest(anna, List.of(nextMonday.plusDays(1)), null, null)).size());
    }

    @Test
    void aRejectedDayCanBeAskedForAgainOnlyAsAnEmergency() {
        LocalDate day = nextMonday.plusDays(2);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        Long id = dayOffService.create(new CreateDayOffRequest(anna, List.of(day), null, null)).getFirst().id();
        TestUsers.signInAs(manager, Role.MANAGER, area);
        dayOffService.changeDecision(id, DayOffStatus.REJECTED, "Store event");

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        BusinessRuleException normal = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(day), null, null)));
        assertTrue(normal.getMessage().contains("emergency"), normal.getMessage());
        // The week still has room for a normal day, yet the rejected day can go as an emergency.
        var sent = dayOffService.create(new CreateDayOffRequest(anna, List.of(day), "Family", true));
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().emergency());
        assertEquals(DayOffStatus.PENDING, sent.getFirst().status());
    }

    @Test
    void managersEditTheirEmployeesButCantChangeRolesOrDeactivate() {
        TestUsers.signInAsAdmin();
        EmployeeResponse a = employeeService.findById(anna);
        EmployeeResponse other = employeeService.findById(otherEmployee);
        EmployeeResponse om = employeeService.findById(otherManager);

        TestUsers.signInAs(manager, Role.MANAGER, area);
        assertEquals("Annie", employeeService.update(anna, edit(a, "Annie", Role.EMPLOYEE, true, null)).firstName());
        assertTrue(employeeService.update(anna, edit(a, "Annie", Role.EMPLOYEE, true, true)).trainee());
        assertThrows(BusinessRuleException.class,
                () -> employeeService.update(anna, edit(a, "Annie", Role.MANAGER, true, null)));
        assertThrows(BusinessRuleException.class,
                () -> employeeService.update(anna, edit(a, "Annie", Role.EMPLOYEE, false, null)));
        assertThrows(AccessDeniedException.class,
                () -> employeeService.update(otherEmployee, edit(other, "Olly", Role.EMPLOYEE, true, null)));
        assertThrows(Exception.class,
                () -> employeeService.update(otherManager, edit(om, "Om", Role.MANAGER, true, null)));
    }

    @Test
    void marketManagersHaveNoHomeArea() {
        TestUsers.signInAsAdmin();
        Long mm = person("P-9", "Max", Role.MARKET_MANAGER, area, store);
        EmployeeResponse saved = employeeService.findById(mm);
        assertNull(saved.areaId());
        assertNull(saved.homeStoreId());
    }
}
