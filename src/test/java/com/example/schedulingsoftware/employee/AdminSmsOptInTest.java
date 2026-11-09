package com.example.schedulingsoftware.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.support.TestUsers;

/** Admins can turn text-message updates on for someone (recording that they agreed); managers can't. */
@SpringBootTest
@Transactional
class AdminSmsOptInTest {

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;

    Long area;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Texts Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private EmployeeRequest request(String phone, Boolean sms) {
        return new EmployeeRequest("TX-1", "Tia", "Text", "tx-1@test.example", phone, Role.EMPLOYEE, area, null, 6,
                true, null, null, null, null, null, null, sms);
    }

    @Test
    void anAdminTurnsTextsOnAndItIsRecorded() {
        EmployeeResponse e = employeeService.create(request("(619) 555-0142", null)).employee();
        assertFalse(e.smsOptIn());

        EmployeeResponse on = employeeService.update(e.id(), request("(619) 555-0142", true));
        assertTrue(on.smsOptIn());
        assertNotNull(on.smsConsentAt());
        assertNotNull(on.smsConsentBy(), "the admin who recorded it");

        // A new number needs agreeing again.
        EmployeeResponse moved = employeeService.update(e.id(), request("(619) 555-0199", null));
        assertFalse(moved.smsOptIn());
        assertNull(moved.smsConsentBy());

        // Turning it off works too.
        employeeService.update(e.id(), request("(619) 555-0199", true));
        assertFalse(employeeService.update(e.id(), request("(619) 555-0199", false)).smsOptIn());
    }

    @Test
    void needsAPhoneNumber() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> employeeService.create(request(null, true)));
        assertTrue(ex.getMessage().contains("mobile number"));
    }

    @Test
    void canBeTurnedOnWhenAddingSomeone() {
        EmployeeResponse e = employeeService.create(request("(619) 555-0142", true)).employee();
        assertTrue(e.smsOptIn());
        assertEquals(e.smsConsentBy(), employeeService.findById(e.id()).smsConsentBy());
    }

    @Test
    void managersCannotTurnItOn() {
        EmployeeResponse e = employeeService.create(request("(619) 555-0142", null)).employee();
        TestUsers.signInAs(999L, Role.MANAGER, area);
        assertThrows(BusinessRuleException.class, () -> employeeService.update(e.id(), request("(619) 555-0142", true)));
    }
}
