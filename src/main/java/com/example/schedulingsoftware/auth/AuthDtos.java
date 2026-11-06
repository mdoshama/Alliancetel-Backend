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
            boolean emailOptIn,
            /** Gets schedule updates by text message. */
            boolean smsOptIn,
            /** Date of birth, or null when not on file. */
            java.time.LocalDate birthDate) {

        public static Me from(Employee e) {
            // Employees aren't told their home store: primary / secondary / floater is for admins and managers.
            boolean showStore = e.getRole() != Role.EMPLOYEE && e.getHomeStore() != null;
            return new Me(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getEmail(), e.getRole(),
                    e.getArea() == null ? null : e.getArea().getId(),
                    e.getArea() == null ? null : e.getArea().getName(),
                    showStore ? e.getHomeStore().getId() : null,
                    showStore ? e.getHomeStore().getName() : null,
                    e.isMustChangePassword(),
                    e.getPhone(),
                    e.isWhatsappOptIn(),
                    e.isEmailOptIn(),
                    e.isSmsOptIn(),
                    e.getBirthDate());
        }
    }

    /** Turn your own WhatsApp (or email) schedule updates on or off. */
    public record WhatsAppPreference(@jakarta.validation.constraints.NotNull Boolean optIn) {
    }

    /**
     * What people can change about themselves: their phone number and birth date. Name, employee code (NTID), email,
     * role, area and store are kept by admins.
     */
    public record ProfileUpdate(
            @Size(max = 30) @jakarta.validation.constraints.Pattern(regexp = com.example.schedulingsoftware.common.InputRules.PHONE_CHARS,
                    message = com.example.schedulingsoftware.common.InputRules.PHONE_CHARS_MSG) String phone,
            @jakarta.validation.constraints.Past(message = "must be in the past") java.time.LocalDate birthDate) {
    }

    /** Choosing a password from the link in the welcome or reset email / text. */
    public record SetupPasswordRequest(@NotBlank @Size(max = 100) String token,
            @NotBlank @Size(max = 100) String newPassword) {
    }

    /** Who a set-password link is for, so the page can greet them. */
    public record SetupLinkInfo(String firstName, String email, boolean newAccount) {
    }

    public record LoginResponse(String token, Instant expiresAt, Me user) {
    }
}
