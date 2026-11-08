package com.example.schedulingsoftware.employee;

public enum Role {
    /** Manages areas and employees across the company. */
    ADMIN,
    /** Builds and publishes weekly schedules, approves day-off requests for an area. */
    MANAGER,
    /** Works shifts and requests days off. */
    EMPLOYEE,
    /** Office staff who clock store staff in and out (watching the store cameras). Only sees the Time Clock. */
    BACK_OFFICE
}
