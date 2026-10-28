package com.example.schedulingsoftware.auth;

import java.time.Instant;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 100) String newPassword) {
    }

    public record Me(
            Long id,
            String employeeCode,
            String fullName,
            String email,
            Role role,
            Long areaId,
            String areaName,
            boolean mustChangePassword) {

        public static Me from(Employee e) {
            return new Me(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getEmail(), e.getRole(),
                    e.getArea() == null ? null : e.getArea().getId(),
                    e.getArea() == null ? null : e.getArea().getName(),
                    e.isMustChangePassword());
        }
    }

    public record LoginResponse(String token, Instant expiresAt, Me user) {
    }
}
