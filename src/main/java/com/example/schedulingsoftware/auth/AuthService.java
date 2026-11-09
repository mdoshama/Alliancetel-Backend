package com.example.schedulingsoftware.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AuthDtos.LoginResponse;
import com.example.schedulingsoftware.auth.AuthDtos.Me;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    /** Same message whatever went wrong, so the login form does not reveal which emails exist. */
    private static final String BAD_LOGIN = "Invalid email or password.";

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccessPolicy accessPolicy;
    private final LoginAttemptService loginAttempts;
    private final RevokedTokenRepository revokedTokens;
    private final Duration temporaryPasswordLifetime;
    private final PasswordSetupService passwordSetup;
    /**
     * Checked when the email is unknown, so a wrong email takes as long as a wrong password and response times
     * don't reveal which emails have accounts.
     */
    private final String dummyHash;

    public AuthService(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            AccessPolicy accessPolicy, LoginAttemptService loginAttempts, RevokedTokenRepository revokedTokens,
            @Value("${alliancetel.security.temporary-password-hours:168}") long temporaryPasswordHours,
            PasswordSetupService passwordSetup) {
        this.passwordSetup = passwordSetup;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accessPolicy = accessPolicy;
        this.loginAttempts = loginAttempts;
        this.revokedTokens = revokedTokens;
        this.temporaryPasswordLifetime = Duration.ofHours(temporaryPasswordHours);
        this.dummyHash = passwordEncoder.encode(PasswordPolicy.generateTemporary());
    }

    @Transactional
    public LoginResponse login(String email, String password, String clientIp) {
        loginAttempts.checkAllowed(email, clientIp);
        Employee e = employeeRepository.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        boolean matches = passwordEncoder.matches(password,
                e == null || e.getPasswordHash() == null ? dummyHash : e.getPasswordHash());
        if (e == null || !e.isActive() || e.getPasswordHash() == null || !matches) {
            loginAttempts.recordFailure(email, clientIp);
            log.info("Failed sign-in for {} from {}", email.trim(), clientIp);
            throw new BadCredentialsException(BAD_LOGIN);
        }
        if (temporaryPasswordExpired(e)) {
            log.info("Expired temporary password used for {} from {}", e.getEmail(), clientIp);
            throw new BadCredentialsException("Your temporary password has expired. Ask your manager or an admin "
                    + "to reset it.");
        }
        loginAttempts.recordSuccess(email);
        // Hashes made with an older, cheaper setting are upgraded the next time the password is known.
        if (passwordEncoder.upgradeEncoding(e.getPasswordHash())) {
            e.setPasswordHash(passwordEncoder.encode(password));
        }
        e.setLastLoginAt(Instant.now());
        log.info("Signed in: {} from {}", e.getEmail(), clientIp);
        return issue(e);
    }

    @Transactional(readOnly = true)
    public Me me() {
        return Me.from(load(accessPolicy.current().id()));
    }

    /** Signs out this device: the token used for this request stops working. Other devices stay signed in. */
    @Transactional
    public void logout() {
        CurrentUser user = accessPolicy.current();
        if (SecurityContextHolder.getContext().getAuthentication().getDetails()
                instanceof JwtService.VerifiedToken token && token.id() != null) {
            revokedTokens.deleteExpired(Instant.now());
            revokedTokens.save(new RevokedToken(token.id(), token.expiresAt()));
        } else {
            // A token from before tokens had ids can only be ended together with the rest.
            load(user.id()).endAllSessions();
        }
        log.info("Signed out: {}", user.email());
    }

    /** Ends every session of the signed-in person: all browsers and phones, including this one. */
    @Transactional
    public void logoutEverywhere() {
        Employee e = load(accessPolicy.current().id());
        e.endAllSessions();
        log.info("Signed out on every device: {}", e.getEmail());
    }

    @Transactional
    public Me setWhatsapp(boolean optIn) {
        Employee e = load(accessPolicy.current().id());
        if (optIn && e.getPhone() == null) {
            throw new BusinessRuleException("There's no phone number on file for you. Ask your manager to add it.");
        }
        e.setWhatsappOptIn(optIn);
        return Me.from(e);
    }

    /** Turns your own text-message updates on (needs a phone number on file) or off. */
    @Transactional
    public Me setSms(boolean optIn) {
        Employee e = load(accessPolicy.current().id());
        if (optIn && e.getPhone() == null) {
            throw new BusinessRuleException("There's no phone number on file for you. Ask your manager to add it.");
        }
        e.setSmsOptIn(optIn);
        if (optIn) {
            e.setSmsConsentAt(java.time.Instant.now());
            e.setSmsConsentBy(null);
            log.info("Text-message consent recorded for employee {}", e.getId());
        }
        return Me.from(e);
    }

    @Transactional
    public Me setEmailUpdates(boolean optIn) {
        Employee e = load(accessPolicy.current().id());
        e.setEmailOptIn(optIn);
        return Me.from(e);
    }

    /**
     * Updates the signed-in person's own phone number and birth date. A new number turns text messages off until
     * they agree again (consent is for a number); no number turns WhatsApp off too.
     */
    @Transactional
    public Me updateProfile(String phone, java.time.LocalDate birthDate) {
        Employee e = load(accessPolicy.current().id());
        String normalized = com.example.schedulingsoftware.common.PhoneNumbers.normalize(phone);
        if (!java.util.Objects.equals(normalized, e.getPhone())) {
            e.setSmsOptIn(false);
        }
        e.setPhone(normalized);
        if (normalized == null) {
            e.setWhatsappOptIn(false);
        }
        e.setBirthDate(com.example.schedulingsoftware.employee.EmployeeService.checkBirthDate(birthDate));
        log.info("Profile updated by employee {}", e.getId());
        return Me.from(e);
    }

    /**
     * Sets a new password and signs out every other session. Wrong current passwords count towards the same
     * lock as failed sign-ins, so a stolen session can't be used to guess the password.
     */
    @Transactional
    public LoginResponse changePassword(String currentPassword, String newPassword, String clientIp) {
        Employee e = load(accessPolicy.current().id());
        loginAttempts.checkAllowed(e.getEmail(), clientIp);
        if (!passwordEncoder.matches(currentPassword, e.getPasswordHash())) {
            loginAttempts.recordFailure(e.getEmail(), clientIp);
            log.info("Wrong current password when changing password: {} from {}", e.getEmail(), clientIp);
            throw new BusinessRuleException("Your current password is not correct.");
        }
        PasswordPolicy.validate(newPassword, e.getFirstName(), e.getLastName(), e.getEmail());
        if (passwordEncoder.matches(newPassword, e.getPasswordHash())) {
            throw new BusinessRuleException("Choose a password different from the current one.");
        }
        e.setPassword(passwordEncoder.encode(newPassword), false);
        loginAttempts.recordSuccess(e.getEmail());
        log.info("Password changed: {} from {}", e.getEmail(), clientIp);
        return issue(e);
    }

    /** Who a set-password link is for (wrong, used and expired links count towards the sign-in lock). */
    @Transactional(readOnly = true)
    public AuthDtos.SetupLinkInfo setupLinkInfo(String token, String clientIp) {
        PasswordSetupToken t = checkLink(token, clientIp);
        return new AuthDtos.SetupLinkInfo(t.getEmployee().getFirstName(), t.getEmployee().getEmail(), t.isNewAccount());
    }

    /**
     * Sets the password chosen from a set-password link and signs them in. The link then stops working, and so does
     * any temporary password; other sessions are signed out.
     */
    @Transactional
    public LoginResponse setupPassword(String token, String newPassword, String clientIp) {
        PasswordSetupToken t = checkLink(token, clientIp);
        Employee e = t.getEmployee();
        PasswordPolicy.validate(newPassword, e.getFirstName(), e.getLastName(), e.getEmail());
        e.setPassword(passwordEncoder.encode(newPassword), false);
        e.setLastLoginAt(Instant.now());
        passwordSetup.used(e);
        loginAttempts.recordSuccess(e.getEmail());
        log.info("Password set from emailed link: {} from {}", e.getEmail(), clientIp);
        return issue(e);
    }

    private PasswordSetupToken checkLink(String token, String clientIp) {
        loginAttempts.checkAllowed("set-password-link@" + clientIp, clientIp);
        try {
            return passwordSetup.find(token);
        } catch (BusinessRuleException ex) {
            loginAttempts.recordFailure("set-password-link@" + clientIp, clientIp);
            throw ex;
        }
    }

    private boolean temporaryPasswordExpired(Employee e) {
        return e.isMustChangePassword() && e.getPasswordSetAt() != null
                && e.getPasswordSetAt().plus(temporaryPasswordLifetime).isBefore(Instant.now());
    }

    private LoginResponse issue(Employee e) {
        JwtService.IssuedToken token = jwtService.issue(e.getId(), e.getTokenVersion());
        return new LoginResponse(token.token(), token.expiresAt(), Me.from(e));
    }

    private Employee load(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new BadCredentialsException(BAD_LOGIN));
    }
}
