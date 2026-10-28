package com.example.schedulingsoftware.schedule;

public enum ScheduleStatus {
    /** Managers can regenerate and edit. */
    DRAFT,
    /** Visible to employees; must be reverted to draft before changes. */
    PUBLISHED
}
