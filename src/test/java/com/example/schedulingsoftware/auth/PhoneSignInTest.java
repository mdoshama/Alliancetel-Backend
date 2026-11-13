package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.support.TestUsers;

/** People can have an email, a phone number or both, and sign in with either. */
@SpringBootTest
@Transactional
class PhoneSignInTest {

    private static final java.util.concurrent.atomic.AtomicInteger RUN = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    AuthService authService;

    Long areaId;
    final int n = RUN.incrementAndGet();
    final String IP = "198.51.100." + n;
    final String phone = "(619) 555-01" + String.format("%02d", 10 + n);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        areaId = areaService.create(new AreaRequest("Phone Area " + n, null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private EmployeeRequest person(String code, String email, String phone) {
        return new EmployeeRequest(code, "Pat", "Lane", email, phone, Role.EMPLOYEE, areaId, null, 6, true, null);
    }

    @Test
    void someoneWithOnlyAPhoneNumberSignsInWithIt() {
        var created = employeeService.create(person("PH-" + n, null, phone), false);
        assertNull(created.employee().email());
        String temp = created.temporaryPassword();
        TestUsers.signOut();
        String digits = phone.replaceAll("\\D", "");
        // However they type it.
        for (String typed : new String[] { phone, digits, "+1" + digits, "+1 " + digits.substring(0, 3) + " "
                + digits.substring(3, 6) + " " + digits.substring(6) }) {
            assertEquals(created.employee().id(), authService.login(typed, temp, IP).user().id(), typed);
        }
        assertThrows(BadCredentialsException.class, () -> authService.login(phone, "wrong-Password-1", IP));
        assertThrows(BadCredentialsException.class, () -> authService.login("not a number", temp, IP));
    }

    @Test
    void someoneWithBothSignsInWithEither() {
        String email = "pat.both" + n + "@test.example";
        var created = employeeService.create(person("PB-" + n, email, phone), false);
        String temp = created.temporaryPassword();
        TestUsers.signOut();
        assertEquals(created.employee().id(), authService.login(email, temp, IP).user().id());
        assertEquals(created.employee().id(), authService.login(phone, temp, IP).user().id());
    }

    @Test
    void anEmailOrAPhoneIsNeededAndEachBelongsToOnePerson() {
        var none = assertThrows(BusinessRuleException.class, () -> employeeService.create(person("PN-" + n, " ", null), false));
        assertTrue(none.getMessage().contains("email or a phone"), none.getMessage());
        employeeService.create(person("P1-" + n, null, phone), false);
        var taken = assertThrows(BusinessRuleException.class,
                () -> employeeService.create(person("P2-" + n, null, phone.replaceAll("\\D", "")), false));
        assertTrue(taken.getMessage().contains("already used"), taken.getMessage());
        // Editing someone with an email: the email can be removed once there's a phone.
        var withEmail = employeeService.create(person("P3-" + n, "pat3." + n + "@test.example", null), false);
        assertThrows(BusinessRuleException.class, () -> employeeService.update(withEmail.employee().id(),
                person("P3-" + n, null, null)));
        var phoneOnly = employeeService.update(withEmail.employee().id(), person("P3-" + n, "", "(858) 555-01" + (10 + n)));
        assertNull(phoneOnly.email());
    }
}
