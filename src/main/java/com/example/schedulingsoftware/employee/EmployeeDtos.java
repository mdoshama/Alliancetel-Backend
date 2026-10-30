package com.example.schedulingsoftware.employee;

import jakarta.validation.constraints.Pattern;
import com.example.schedulingsoftware.common.InputRules;
import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record EmployeeRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = InputRules.CODE, message = InputRules.CODE_MSG) String employeeCode,
            @NotBlank @Size(max = 60) @Pattern(regexp = InputRules.PERSON_NAME, message = InputRules.PERSON_NAME_MSG) String firstName,
            @NotBlank @Size(max = 60) @Pattern(regexp = InputRules.PERSON_NAME, message = InputRules.PERSON_NAME_MSG) String lastName,
            @NotBlank @Size(max = 120) @Pattern(regexp = InputRules.EMAIL, message = InputRules.EMAIL_MSG) String email,
            /** Any common format; saved in international form (+16195550142). */
            @Size(max = 30) @Pattern(regexp = InputRules.PHONE_CHARS, message = InputRules.PHONE_CHARS_MSG) String phone,
            @NotNull Role role,
            Long areaId,
            Long homeStoreId,
            @NotNull @Min(1) @Max(7) Integer workDaysPerWeek,
            Boolean active,
            /** Primary at the home store (true) or secondary (false). Null keeps the current setting. */
            Boolean primaryAtStore,
            /** Agreed to WhatsApp schedule updates (needs a phone number). Null keeps the current setting. */
            Boolean whatsappOptIn,
            /** Schedule updates by email. Null keeps the current setting. */
            Boolean emailOptIn) {

        public EmployeeRequest(String employeeCode, String firstName, String lastName, String email, String phone,
                Role role, Long areaId, Long homeStoreId, Integer workDaysPerWeek, Boolean active, Boolean primaryAtStore) {
            this(employeeCode, firstName, lastName, email, phone, role, areaId, homeStoreId, workDaysPerWeek, active,
                    primaryAtStore, null, null);
        }

        public EmployeeRequest(String employeeCode, String firstName, String lastName, String email, String phone,
                Role role, Long areaId, Long homeStoreId, Integer workDaysPerWeek, Boolean active, Boolean primaryAtStore,
                Boolean whatsappOptIn) {
            this(employeeCode, firstName, lastName, email, phone, role, areaId, homeStoreId, workDaysPerWeek, active,
                    primaryAtStore, whatsappOptIn, null);
        }
    }

    /** Quick reassignment of someone's home store; null clears it. */
    public record HomeStoreRequest(Long storeId, Boolean primary) {
    }

    public record EmployeeResponse(
            Long id,
            String employeeCode,
            String firstName,
            String lastName,
            String fullName,
            String email,
            String phone,
            Role role,
            Long areaId,
            String areaName,
            Long homeStoreId,
            String homeStoreName,
            StoreRole storeRole,
            int workDaysPerWeek,
            boolean active,
            boolean loginEnabled,
            Instant lastLoginAt,
            boolean whatsappOptIn,
            boolean emailOptIn) {

        public static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(
                    e.getId(),
                    e.getEmployeeCode(),
                    e.getFirstName(),
                    e.getLastName(),
                    e.getFullName(),
                    e.getEmail(),
                    e.getPhone(),
                    e.getRole(),
                    e.getArea() == null ? null : e.getArea().getId(),
                    e.getArea() == null ? null : e.getArea().getName(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getId(),
                    e.getHomeStore() == null ? null : e.getHomeStore().getName(),
                    e.getStoreRole(),
                    e.getWorkDaysPerWeek(),
                    e.isActive(),
                    e.getPasswordHash() != null,
                    e.getLastLoginAt(),
                    e.isWhatsappOptIn(),
                    e.isEmailOptIn());
        }
    }

    /** Returned once when an account is created or its password reset; the password is never stored in plain text. */
    public record CredentialsResponse(EmployeeResponse employee, String temporaryPassword) {
    }
}
