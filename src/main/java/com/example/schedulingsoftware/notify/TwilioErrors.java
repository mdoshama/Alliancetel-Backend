package com.example.schedulingsoftware.notify;

import java.util.Map;

/** Plain-language advice for the Twilio error codes people are most likely to see. */
final class TwilioErrors {

    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("21608", "The texting account is still a trial: it can only text numbers verified on the account."),
            Map.entry("21610", "They replied STOP; they can text START to get messages again."),
            Map.entry("21211", "The phone number isn't valid; check it on the employee's details."),
            Map.entry("30003", "The phone is off or out of service."),
            Map.entry("30005", "The number doesn't exist or isn't a mobile."),
            Map.entry("30006", "That number is a landline or can't receive texts."),
            Map.entry("30007", "The carrier filtered it; the texting number's carrier (A2P 10DLC) registration needs to be finished."),
            Map.entry("30034", "The sending number isn't registered for A2P 10DLC yet."),
            Map.entry("63015", "They haven't joined the WhatsApp test sandbox yet: they need to send the join phrase first."),
            Map.entry("63016", "Outside WhatsApp's 24-hour window, so it needs an approved message template: set up the four "
                    + "WhatsApp templates in the app's settings (on the test sandbox, the person can message it again to "
                    + "reopen 24 hours), then Try again."),
            Map.entry("63003", "That number isn't on WhatsApp."),
            Map.entry("63024", "That number isn't on WhatsApp."),
            Map.entry("63112", "The WhatsApp sender isn't approved or is restricted by Meta."));

    private TwilioErrors() {
    }

    static String hint(String code) {
        return code == null ? null : HINTS.get(code);
    }
}
