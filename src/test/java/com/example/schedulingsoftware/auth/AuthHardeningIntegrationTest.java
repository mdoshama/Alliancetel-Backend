package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.CredentialsResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.support.TestUsers;

/** Sessions end when they should, passwords are strong, and sign-in gives nothing away. */
@SpringBootTest
@Transactional
class AuthHardeningIntegrationTest {

    /** Each test gets its own person and address: failed attempts are remembered in memory across tests. */
    private static final java.util.concurrent.atomic.AtomicInteger RUN = new java.util.concurrent.atomic.AtomicInteger();
    private static final String STRONG = "Harbor-Lamp-482";

    @Autowired
    AreaService areaService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    AuthService authService;
    @Autowired
    JwtService jwtService;
    @Autowired
    RevokedTokenRepository revokedTokens;

    Long areaId;
    Long employeeId;
    String temporary;
    final int n = RUN.incrementAndGet();
    final String IP = "203.0.113." + n;
    final String code = "AU-" + n;
    final String email = "dana.reyes" + n + "@test.example";

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        areaId = areaService.create(new AreaRequest("Auth Test", null, true)).id();
        CredentialsResponse created = employeeService.create(new EmployeeRequest(code, "Dana", "Reyes", email,
                null, Role.EMPLOYEE, areaId, null, 6, true, null));
        employeeId = created.employee().id();
        temporary = created.temporaryPassword();
        TestUsers.signOut();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    // ---- sessions ----

    @Test
    void signOutEndsOnlyThisDevicesToken() {
        String phone = authService.login(email, temporary, IP).token();
        String laptop = authService.login(email, temporary, IP).token();
        assertTrue(accepted(phone) && accepted(laptop));

        signInWith(laptop);
        authService.logout();

        assertFalse(accepted(laptop), "the signed-out token is refused even though it hasn't expired");
        assertTrue(accepted(phone), "the phone stays signed in");
        assertEquals(1, revokedTokens.count());
    }

    @Test
    void signOutEverywhereEndsEveryToken() {
        String phone = authService.login(email, temporary, IP).token();
        String laptop = authService.login(email, temporary, IP).token();

        signInWith(laptop);
        authService.logoutEverywhere();

        assertFalse(accepted(phone));
        assertFalse(accepted(laptop));
        assertTrue(accepted(authService.login(email, temporary, IP).token()), "signing in again works");
    }

    @Test
    void expiredSignOutRecordsAreCleanedUp() {
        revokedTokens.save(new RevokedToken("old-token-id", Instant.now().minus(Duration.ofHours(1))));
        signInWith(authService.login(email, temporary, IP).token());
        authService.logout();
        assertFalse(revokedTokens.existsById("old-token-id"));
    }

    @Test
    void changingThePasswordEndsOtherSessionsButKeepsTheNewOne() {
        String other = authService.login(email, temporary, IP).token();
        String mine = authService.login(email, temporary, IP).token();
        signInWith(mine);

        String fresh = authService.changePassword(temporary, STRONG, IP).token();

        assertFalse(accepted(other));
        assertFalse(accepted(mine));
        assertTrue(accepted(fresh));
        assertThrows(BadCredentialsException.class, () -> authService.login(email, temporary, IP));
        assertDoesNotThrow(() -> authService.login(email, STRONG, IP));
    }

    @Test
    void adminResetDeactivationAndRoleChangeEndSessions() {
        String token = authService.login(email, temporary, IP).token();
        TestUsers.signInAsAdmin();
        String reset = employeeService.resetPassword(employeeId).temporaryPassword();
        assertFalse(accepted(token), "reset signs them out");

        token = authService.login(email, reset, IP).token();
        TestUsers.signInAsAdmin();
        employeeService.update(employeeId, new EmployeeRequest(code, "Dana", "Reyes", email, null, Role.MANAGER,
                areaId, null, 6, true, null));
        assertFalse(accepted(token), "a role change signs them out");

        token = authService.login(email, reset, IP).token();
        TestUsers.signInAsAdmin();
        employeeService.update(employeeId, new EmployeeRequest(code, "Dana", "Reyes", email, null, Role.MANAGER,
                areaId, null, 5, true, null));
        assertTrue(accepted(token), "ordinary edits keep them signed in");

        TestUsers.signInAsAdmin();
        employeeService.deactivate(employeeId);
        assertFalse(accepted(token));
    }

    @Test
    void forgedOrTamperedTokensAreRefused() {
        String token = authService.login(email, temporary, IP).token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertFalse(accepted(tampered));
        assertFalse(accepted(parts[0] + "." + parts[1] + "."), "unsigned token");
        assertFalse(accepted("not-a-token"));
        JwtService otherKey = new JwtService("someone-elses-secret-0123456789-abcdefghijk", 12, false);
        assertFalse(accepted(otherKey.issue(employeeId, 0).token()), "token signed with another key");
    }

    // ---- sign-in ----

    @Test
    void temporaryPasswordsExpire() {
        Employee e = employeeRepository.findById(employeeId).orElseThrow();
        e.setPasswordSetAt(Instant.now().minus(Duration.ofDays(8)));
        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authService.login(email, temporary, IP));
        assertTrue(ex.getMessage().contains("expired"));
        // A wrong password still gets the usual answer, so expiry only shows after the right password.
        assertEquals("Invalid email or password.", assertThrows(BadCredentialsException.class,
                () -> authService.login(email, "Wrong-Guess-91", IP)).getMessage());
    }

    @Test
    void unknownEmailAndWrongPasswordLookTheSame() {
        String unknown = assertThrows(BadCredentialsException.class,
                () -> authService.login("nobody@test.example", "Wrong-Guess-91", IP)).getMessage();
        String wrong = assertThrows(BadCredentialsException.class,
                () -> authService.login(email, "Wrong-Guess-91", "198.51.100." + n)).getMessage();
        assertEquals(unknown, wrong);
    }

    @Test
    void guessingTheCurrentPasswordWithAStolenSessionIsLimited() {
        signInWith(authService.login(email, temporary, IP).token());
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessRuleException.class, () -> authService.changePassword("Wrong-Guess-91", STRONG, IP));
        }
        assertThrows(LoginAttemptService.TooManyAttemptsException.class,
                () -> authService.changePassword(temporary, STRONG, IP));
    }

    @Test
    void newPasswordMustBeStrongAndPersonal() {
        signInWith(authService.login(email, temporary, IP).token());
        assertThrows(BusinessRuleException.class, () -> authService.changePassword(temporary, "DanaReyes2026", IP));
        assertThrows(BusinessRuleException.class, () -> authService.changePassword(temporary, "Welcome2026!", IP));
        assertDoesNotThrow(() -> authService.changePassword(temporary, STRONG, IP));
    }

    @Test
    void passwordRules() {
        String[] weak = { "Short1", "Welcome123", "Welcome2026!", "P@ssw0rd2026", "Password1234", "abcd123456",
                "qwerty12345", "12345678ab", "Alliancetel1", "aaaaaaaa11", "abababab12", "NoDigitsHere",
                "1234567890", " Spaced-Out-5", "x".repeat(70) + "1" };
        for (String p : weak) {
            assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate(p), p);
        }
        assertThrows(BusinessRuleException.class, () -> PasswordPolicy.validate("reyes-blue-77", "Dana", "Reyes", email));
        assertThrows(BusinessRuleException.class,
                () -> PasswordPolicy.validate("dana.reyes-9x", "Dana", "Reyes", email));
        for (String p : new String[] { STRONG, "Blue-Harbor-47", "tacos4breakfast", "MyDogMax2019!", "Kx7mPq2rTn" }) {
            assertDoesNotThrow(() -> PasswordPolicy.validate(p, "Dana", "Reyes", email), p);
        }
        String generated = PasswordPolicy.generateTemporary();
        assertEquals(12, generated.length());
        assertNotEquals(generated, PasswordPolicy.generateTemporary());
    }

    // ---- helpers ----

    /** Runs the real token filter and reports whether the request ended up signed in. */
    private boolean accepted(String token) {
        var saved = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
            request.addHeader("Authorization", "Bearer " + token);
            new JwtAuthenticationFilter(jwtService, employeeRepository, revokedTokens)
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            return CurrentUser.find().isPresent();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(saved);
        }
    }

    private void signInWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        try {
            new JwtAuthenticationFilter(jwtService, employeeRepository, revokedTokens)
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(CurrentUser.find().isPresent());
    }
}
