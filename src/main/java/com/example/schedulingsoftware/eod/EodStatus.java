package com.example.schedulingsoftware.eod;

/** Where a store's end-of-day report is. */
public enum EodStatus {
    /** Reps are logging sales; the closer hasn't submitted yet. */
    OPEN,
    /** Closed out for the day; locked for reps. A manager can approve or reopen it. */
    SUBMITTED,
    /** Checked by a manager. Reopening it is the only way to change it. */
    APPROVED
}
