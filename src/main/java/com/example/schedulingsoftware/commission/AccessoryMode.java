package com.example.schedulingsoftware.commission;

/** How the accessory brackets apply to a month's net accessories. */
public enum AccessoryMode {
    /** The whole amount at the rate of the bracket reached ($4,000 → 10% of $4,000). */
    WHOLE,
    /** Each part at its own bracket's rate ($3,000 at 2% + $1,000 at 10%). */
    BRACKETS
}
