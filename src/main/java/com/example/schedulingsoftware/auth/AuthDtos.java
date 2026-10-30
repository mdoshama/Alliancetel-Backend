package com.example.schedulingsoftware.auth;

import java.time.Instant;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 120) String email, @NotBlank @Size(max = 100) String password) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 100) String currentPassword,
            @NotBlank @Size(max = 100) String newPassword) {
    }

    public record Me(
            Long id,
            String employeeCode,
            String fullName,
            String email,
            Role role,
            Long areaId,
            String areaName,
            /** Where they normally work (null for floaters and most admins). */
            Long homeStoreId,
            String homeStoreName,
            boolean mustChangePassword,
            /** International form, e.g. +16195550142; null if none on file. */
            String phone,
            /** Gets schedule updates on WhatsApp. */
            boolean whatsappOptIn,
            /** Gets schedule updates by email. */
            boolean emailOptIn) {

        public static Me from(Employee e) {
            return new Me(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getEmail(), e.getRole(),
                    e.getArea() == null ? null : e.getArea().getId(),
                    e.getArea() == null ? null : e.getArea().getName(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getId(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getName(),
                    e.isMustChangePassword(),
                    e.getPhone(),
                    e.isWhatsappOptIn(),
                    e.isEmailOptIn());
        }
    }

    /** Turn your own WhatsApp (or email) schedule updates on or off. */
    public record WhatsAppPreference(@jakarta.validation.constraints.NotNull Boolean optIn) {
    }

    public record LoginResponse(String token, Instant expiresAt, Me user) {
    }
}
