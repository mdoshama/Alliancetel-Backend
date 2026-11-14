package com.example.schedulingsoftware.employee;

public enum Role {
    /** Manages areas and employees across the company. */
    ADMIN,
    /** Builds and publishes weekly schedules, approves day-off requests for an area. */
    MANAGER,
    /**
     * Market Manager: a manager of several areas. An admin assigns the areas; they manage each one like that area's
     * manager (and their own home area). They can also work shifts in their home area.
     */
    MARKET_MANAGER,
    /** Works shifts and requests days off. */
    EMPLOYEE,
    /** Office staff who clock store staff in and out (watching the store cameras). Only sees the Time Clock. */
    BACK_OFFICE
}
