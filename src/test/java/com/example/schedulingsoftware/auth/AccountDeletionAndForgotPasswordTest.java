package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.notify.NotificationKind;
import com.example.schedulingsoftware.notify.NotificationRepository;
import com.example.schedulingsoftware.support.TestUsers;

/** Deleting your own account (App Store / Google Play requirement) and "Forgot password?". */
@SpringBootTest(properties = "alliancetel.app-url=https://app.test.example")
@Transactional
class AccountDeletionAndForgotPasswordTest {

    static final String PASSWORD = "Harbor7Lights";

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employees;
    @Autowired
    AuthService authService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    PasswordEncoder passwordEncoder;

    Long area;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Delete Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    /** A person with a known password, signed in as themselves. */
    private Employee person(String code, Role role, String phone) {
        TestUsers.signInAsAdmin();
        Long id = employeeService.create(new EmployeeRequest(code, "Dana", "Doe", code.toLowerCase() + "@test.example",
                phone, role, area, null, 6, true, null), true).employee().id();
        Employee e = employees.findById(id).orElseThrow();
        e.setPassword(passwordEncoder.encode(PASSWORD), false);
        e.setBirthDate(LocalDate.now().minusYears(30));
        TestUsers.signInAs(id, role, area);
        return e;
    }

    private long messagesFor(Long id) {
        return notifications.findAll().stream().filter(n -> n.getEmployee() != null && n.getEmployee().getId().equals(id))
                .count();
    }

    @Test
    void deletingYourAccountErasesYourDetailsAndSignsYouOut() {
        Employee e = person("DL-1", Role.EMPLOYEE, "(619) 555-0177");
        Long id = e.getId();
        int sessionsBefore = e.getTokenVersion();
        assertTrue(messagesFor(id) > 0, "the welcome message is on file");

        assertThrows(BusinessRuleException.class, () -> authService.deleteOwnAccount("wrong-password", "10.1.0.1"));
        assertTrue(employees.findById(id).orElseThrow().isActive(), "a wrong password changes nothing");

        authService.deleteOwnAccount(PASSWORD, "10.1.0.1");

        Employee gone = employees.findById(id).orElseThrow();
        assertFalse(gone.isActive());
        assertNotNull(gone.getDeletedAt());
        assertEquals("Deleted user", gone.getFullName());
        assertEquals("DEL-" + id, gone.getEmployeeCode());
        assertNull(gone.getEmail());
        assertNull(gone.getPhone());
        assertNull(gone.getBirthDate());
        assertNull(gone.getPasswordHash());
        assertFalse(gone.isSmsOptIn() || gone.isWhatsappOptIn() || gone.isEmailOptIn());
        assertTrue(gone.getTokenVersion() > sessionsBefore, "signed out on every device");
        assertEquals(0, messagesFor(id), "their messages are erased");

        TestUsers.signOut();
        assertThrows(BadCredentialsException.class, () -> authService.login("dl-1@test.example", PASSWORD, "10.1.0.1"));

        // Admins can't bring a deleted account back.
        TestUsers.signInAsAdmin();
        assertThrows(BusinessRuleException.class, () -> employeeService.resetPassword(id, false));
    }

    @Test
    void theLastAdminCantDeleteThemselves() {
        Employee admin = person("DL-2", Role.ADMIN, null);
        employees.findAll().stream()
                .filter(x -> x.getRole() == Role.ADMIN && x.isActive() && !x.getId().equals(admin.getId()))
                .forEach(x -> x.setActive(false));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> authService.deleteOwnAccount(PASSWORD, "10.1.0.2"));
        assertTrue(ex.getMessage().contains("only admin"), ex.getMessage());
        assertTrue(employees.findById(admin.getId()).orElseThrow().isActive());

        person("DL-3", Role.ADMIN, null);
        TestUsers.signInAs(admin.getId(), Role.ADMIN, area);
        authService.deleteOwnAccount(PASSWORD, "10.1.0.2");
        assertFalse(employees.findById(admin.getId()).orElseThrow().isActive(), "fine once there's another admin");
    }

    @Test
    void forgotPasswordSendsALinkWithoutChangingThePassword() {
        Employee e = person("FP-1", Role.EMPLOYEE, null);
        String hashBefore = e.getPasswordHash();
        TestUsers.signOut();
        long before = notifications.findAll().stream().filter(n -> n.getKind() == NotificationKind.ACCOUNT_SETUP).count();

        authService.forgotPassword("  FP-1@test.example ", "10.1.0.3");

        long after = notifications.findAll().stream().filter(n -> n.getKind() == NotificationKind.ACCOUNT_SETUP).count();
        assertEquals(before + 1, after, "a set-password link was queued");
        assertEquals(hashBefore, employees.findById(e.getId()).orElseThrow().getPasswordHash(),
                "the current password keeps working until a new one is chosen");
        assertNotNull(authService.login("fp-1@test.example", PASSWORD, "10.1.0.3").token());

        // Unknown accounts: same (silent) answer, nothing sent.
        authService.forgotPassword("nobody@test.example", "10.1.0.3");
        assertEquals(after, notifications.findAll().stream().filter(n -> n.getKind() == NotificationKind.ACCOUNT_SETUP)
                .count());
    }

    @Test
    void forgotPasswordIsLimitedPerAccount() {
        Employee e = person("FP-2", Role.EMPLOYEE, null);
        TestUsers.signOut();
        for (int i = 0; i < 6; i++) {
            authService.forgotPassword("fp-2@test.example", "10.1.0." + (10 + i));
        }
        long sent = notifications.findAll().stream()
                .filter(n -> n.getKind() == NotificationKind.ACCOUNT_SETUP && n.getEmployee().getId().equals(e.getId()))
                .count();
        // The welcome email plus at most 3 requested links.
        assertTrue(sent <= 4, "sent " + sent);
        // Asking for links never blocks signing in.
        assertNotNull(authService.login("fp-2@test.example", PASSWORD, "10.1.0.10").token());
    }
}
