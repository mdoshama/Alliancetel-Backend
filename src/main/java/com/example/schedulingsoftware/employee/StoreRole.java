package com.example.schedulingsoftware.employee;

/** How someone is tied to a store. */
public enum StoreRole {
    /** Anchored to their home store: Generate and Auto-fill never move them; only a manager can, by hand. */
    PRIMARY,
    /** Based at a home store, but Auto-fill may send them to cover another store. */
    SECONDARY,
    /** No home store: Auto-fill places them wherever people are needed. */
    FLOATER
}
