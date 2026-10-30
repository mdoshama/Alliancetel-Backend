package com.example.schedulingsoftware.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.PasswordPolicy;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Guarantees someone can always sign in: if no active admin has a password, the configured
 * bootstrap admin gets one (created if needed) and must change it at first login.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${alliancetel.bootstrap-admin.email:admin@alliancetel.example}")
    private String email;

    @Value("${alliancetel.bootstrap-admin.password:ChangeMe123}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (employeeRepository.existsByRoleAndActiveTrueAndPasswordHashIsNotNull(Role.ADMIN)) {
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("No administrator can sign in yet. Set ADMIN_EMAIL and ADMIN_PASSWORD "
                    + "for the first start; the admin must change the password after signing in.");
        }
        try {
            PasswordPolicy.validateBootstrap(password);
        } catch (RuntimeException e) {
            throw new IllegalStateException("ADMIN_PASSWORD is too weak: " + e.getMessage(), e);
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Employee admin = employeeRepository.findByEmailIgnoreCase(normalized)
                .or(() -> employeeRepository.findFirstByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN))
                .orElseGet(() -> {
                    Employee e = new Employee();
                    e.setEmployeeCode("AT-0001");
                    e.setFirstName("System");
                    e.setLastName("Admin");
                    e.setEmail(normalized);
                    return e;
                });
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        admin.setPassword(passwordEncoder.encode(password), true);
        employeeRepository.save(admin);
        log.warn("No admin could sign in. Bootstrap admin is {} with the configured initial password; "
                + "it must be changed at first login.", admin.getEmail());
    }
}
