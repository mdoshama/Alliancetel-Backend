package com.example.schedulingsoftware.auth;

import java.time.Instant;
import java.util.Locale;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AuthDtos.LoginResponse;
import com.example.schedulingsoftware.auth.AuthDtos.Me;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** Same message whatever went wrong, so the login form does not reveal which emails exist. */
    private static final String BAD_LOGIN = "Invalid email or password.";

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccessPolicy accessPolicy;
    private final LoginAttemptService loginAttempts;

    @Transactional
    public LoginResponse login(String email, String password, String clientIp) {
        loginAttempts.checkAllowed(email, clientIp);
        Employee e = employeeRepository.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        if (e == null || !e.isActive() || e.getPasswordHash() == null
                || !passwordEncoder.matches(password, e.getPasswordHash())) {
            loginAttempts.recordFailure(email, clientIp);
            throw new BadCredentialsException(BAD_LOGIN);
        }
        loginAttempts.recordSuccess(email);
        e.setLastLoginAt(Instant.now());
        return issue(e);
    }

    @Transactional(readOnly = true)
    public Me me() {
        return Me.from(load(accessPolicy.current().id()));
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

    @Transactional
    public Me setEmailUpdates(boolean optIn) {
        Employee e = load(accessPolicy.current().id());
        e.setEmailOptIn(optIn);
        return Me.from(e);
    }

    @Transactional
    public LoginResponse changePassword(String currentPassword, String newPassword) {
        Employee e = load(accessPolicy.current().id());
        if (!passwordEncoder.matches(currentPassword, e.getPasswordHash())) {
            throw new BusinessRuleException("Your current password is not correct.");
        }
        PasswordPolicy.validate(newPassword);
        if (passwordEncoder.matches(newPassword, e.getPasswordHash())) {
            throw new BusinessRuleException("Choose a password different from the current one.");
        }
        e.setPasswordHash(passwordEncoder.encode(newPassword));
        e.setMustChangePassword(false);
        return issue(e);
    }

    private LoginResponse issue(Employee e) {
        JwtService.IssuedToken token = jwtService.issue(e.getId());
        return new LoginResponse(token.token(), token.expiresAt(), Me.from(e));
    }

    private Employee load(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new BadCredentialsException(BAD_LOGIN));
    }
}
