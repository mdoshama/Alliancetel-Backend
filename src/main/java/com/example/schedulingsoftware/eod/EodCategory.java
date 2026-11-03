package com.example.schedulingsoftware.eod;

/**
 * Sale categories, exactly as on the store's daily sheet. Commission is counted by these, so a sale always has one.
 */
public enum EodCategory {
    NL_AAL("NL/AAL", "New line / add a line"),
    BTS("BTS", "Tablet"),
    UPG("UPG", "Upgrade"),
    FREE_LINE("FREE LINE", "Free line"),
    REACT("REACT", "Reactivation"),
    UNLOCK("UNLOCK", "Unlock (last 4 of IMEI and phone number)"),
    M_LOCK("M.LOCK", "Metro locked / used phone (last 4 of IMEI and phone number)"),
    HINT("HINT", "T-Mobile Home Internet");

    private final String label;
    private final String help;

    EodCategory(String label, String help) {
        this.label = label;
        this.help = help;
    }

    public String getLabel() {
        return label;
    }

    public String getHelp() {
        return help;
    }

    /** Unlock and Metro-locked sales need the last 4 of the IMEI and the phone number. */
    public boolean needsDevice() {
        return this == UNLOCK || this == M_LOCK;
    }
}
