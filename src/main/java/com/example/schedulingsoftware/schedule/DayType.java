package com.example.schedulingsoftware.schedule;

public enum DayType {
    /** Scheduled to work. */
    WORK,
    /** Regular weekly day off assigned by the scheduler or a manager. */
    OFF,
    /** Day off the employee asked for and a manager approved. */
    REQUESTED_OFF
}
