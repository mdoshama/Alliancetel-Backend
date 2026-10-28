package com.example.schedulingsoftware.employee;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record EmployeeRequest(
            @NotBlank @Size(max = 20) String employeeCode,
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @NotBlank @Email @Size(max = 120) String email,
            @Size(max = 30) String phone,
            @NotNull Role role,
            Long areaId,
            Long homeStoreId,
            @NotNull @Min(1) @Max(7) Integer workDaysPerWeek,
            Boolean active) {
    }

    /** Quick reassignment of someone's home store; null clears it. */
    public record HomeStoreRequest(Long storeId) {
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
            int workDaysPerWeek,
            boolean active,
            boolean loginEnabled,
            Instant lastLoginAt) {

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
                    e.getWorkDaysPerWeek(),
                    e.isActive(),
                    e.getPasswordHash() != null,
                    e.getLastLoginAt());
        }
    }

    /** Returned once when an account is created or its password reset; the password is never stored in plain text. */
    public record CredentialsResponse(EmployeeResponse employee, String temporaryPassword) {
    }
}
