package com.example.schedulingsoftware.commission;

/** Fines and rewards from the compensation sheet, plus "other" for anything else. */
public enum AdjustmentType {
    /** Fined per minute late. */
    LATE("Late", false, true),
    /** After the 3rd in a month the employee is let go (the app warns). */
    NO_CALL_NO_SHOW("No call, no show", false, false),
    NO_UNIFORM("No uniform", false, false),
    MISSED_MEETING("Missed meeting", false, false),
    MISSED_SYNC_MEETING("Missed Sync Sphere meeting", false, false),
    SCORECARD_REWARD("Scorecard below 1000 ranking", true, false),
    OTHER_FINE("Other fine", false, false),
    OTHER_BONUS("Other bonus", true, false);

    private final String label;
    private final boolean reward;
    private final boolean perMinute;

    AdjustmentType(String label, boolean reward, boolean perMinute) {
        this.label = label;
        this.reward = reward;
        this.perMinute = perMinute;
    }

    public String getLabel() {
        return label;
    }

    public boolean isReward() {
        return reward;
    }

    public boolean isPerMinute() {
        return perMinute;
    }
}
