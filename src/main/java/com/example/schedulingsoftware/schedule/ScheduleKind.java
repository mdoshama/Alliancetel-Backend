package com.example.schedulingsoftware.schedule;

/**
 * Which draft of a week a schedule is. Each area and week has at most one of each; only {@link #MAIN} is ever published
 * (publishing another draft copies it into the main one).
 */
public enum ScheduleKind {
    /** The week's schedule: built by "Generate" (or copied from a draft) and the one staff see once published. */
    MAIN,
    /** Starts blank: a manager places everyone by hand. Approved days off are shown, locked. */
    EMPTY,
    /** Starts with only the days off filled in (approved requests and regular days); the rest by hand or by the system. */
    DAYS_OFF,
    /**
     * "Schedule by System": the system builds the week by itself and keeps it current while nobody has edited it. Once a
     * manager or admin changes a day it stays as they left it. Publishing it copies it into {@link #MAIN}.
     */
    SYSTEM
}
