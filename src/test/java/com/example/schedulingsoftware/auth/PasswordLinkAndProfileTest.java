package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.notify.Notification;
import com.example.schedulingsoftware.notify.NotificationChannel;
import com.example.schedulingsoftware.notify.NotificationKind;
import com.example.schedulingsoftware.notify.NotificationRepository;
import com.example.schedulingsoftware.support.TestUsers;

/** The set-password link sent to new and reset accounts, and people editing their own profile. */
@SpringBootTest(properties = "alliancetel.app-url=https://app.test.example")
@Transactional
class PasswordLinkAndProfileTest {

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

    Long area;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Link Area", null, true)).id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private CredentialsResponse create(String code, String phone) {
        return employeeService.create(new EmployeeRequest(code, "Lia", "Linker", code.toLowerCase() + "@test.example",
                phone, Role.EMPLOYEE, area, null, 6, true, null), true);
    }

    private List<Notification> setupMessages(Long id) {
        return notifications.findAll().stream()
                .filter(n -> n.getKind() == NotificationKind.ACCOUNT_SETUP && n.getEmployee().getId().equals(id))
                .toList();
    }

    /** The secret from the queued email's link. */
    private String secretFrom(Long id) {
        String body = setupMessages(id).stream().filter(n -> n.getChannel() == NotificationChannel.EMAIL)
                .reduce((a, b) -> b).orElseThrow().getBody();
        int at = body.indexOf("/set-password?t=");
        assertTrue(at > 0, body);
        return body.substring(at + "/set-password?t=".length()).split("\\s")[0];
    }

    @Test
    void newAccountGetsAnEmailWithALinkThatSetsThePassword() {
        CredentialsResponse c = create("LK-1", null);
        Long id = c.employee().id();
        assertNotNull(c.temporaryPassword(), "the temporary password stays as a backup");
        List<Notification> sent = setupMessages(id);
        assertEquals(1, sent.size(), "email only: no phone");
        Notification mail = sent.getFirst();
        assertTrue(mail.getBody().contains("https://app.test.example/set-password?t="), mail.getBody());
        assertFalse(mail.getPreview().contains("set-password?t="), "the Messages page never shows the link");

        String secret = secretFrom(id);
        TestUsers.signOut();
        assertTrue(authService.setupLinkInfo(secret, "10.0.0.1").newAccount());
        var login = authService.setupPassword(secret, "Harbor7Lights", "10.0.0.1");
        assertNotNull(login.token());
        assertFalse(employees.findById(id).orElseThrow().isMustChangePassword());
        // Works once only.
        assertThrows(BusinessRuleException.class, () -> authService.setupPassword(secret, "Another8Pass", "10.0.0.1"));
        assertThrows(BusinessRuleException.class, () -> authService.setupLinkInfo("nonsense", "10.0.0.1"));
    }

    @Test
    void aResetSendsANewLinkAndTheOldOneStopsWorking() {
        Long id = create("LK-2", null).employee().id();
        String first = secretFrom(id);
        employeeService.resetPassword(id, false);
        String second = secretFrom(id);
        TestUsers.signOut();
        assertThrows(BusinessRuleException.class, () -> authService.setupLinkInfo(first, "10.0.0.2"));
        assertFalse(authService.setupLinkInfo(second, "10.0.0.2").newAccount());
    }

    @Test
    void peopleCanUpdateTheirOwnPhoneAndBirthDate() {
        Long id = create("LK-3", "(619) 555-0142").employee().id();
        var e = employees.findById(id).orElseThrow();
        e.setSmsOptIn(true);
        TestUsers.signInAs(id, Role.EMPLOYEE, area);

        var me = authService.updateProfile("(619) 555-0142", LocalDate.now().minusYears(30));
        assertEquals(LocalDate.now().minusYears(30), me.birthDate());
        assertTrue(me.smsOptIn(), "same number keeps texts on");

        me = authService.updateProfile("760-555-0199", me.birthDate());
        assertEquals("+17605550199", me.phone());
        assertFalse(me.smsOptIn(), "a new number needs agreeing to texts again");
        // Name, code and email aren't part of the profile update and stay as the admin set them.
        assertEquals("LK-3", me.employeeCode());

        assertThrows(BusinessRuleException.class, () -> authService.updateProfile(null, LocalDate.now().minusYears(3)));
        me = authService.updateProfile(null, null);
        assertNull(me.phone());
        assertNull(me.birthDate());
    }
}
