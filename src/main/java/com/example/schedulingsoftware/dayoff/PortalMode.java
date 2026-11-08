package com.example.schedulingsoftware.dayoff;

/** Whether people can send their own day-off requests (emergencies are always allowed). */
public enum PortalMode {
    /** Normal weekly cycle: next week's requests close at the end of Friday; the week after opens on Saturday. */
    AUTO,
    /** Opened by an admin: next week takes requests even after Friday, as well as the week after. */
    OPEN,
    /** Closed by an admin: no new requests until it's opened again (emergencies still go through). */
    CLOSED
}
