package com.example.schedulingsoftware.common;

/**
 * Turns a phone number as people type it into international (E.164) form, e.g. "(619) 555-0142" → "+16195550142",
 * which is also what WhatsApp needs. Numbers without a country code are taken as US/Canada numbers.
 */
public final class PhoneNumbers {

    public static final String INVALID = "Phone must be a valid number, e.g. (619) 555-0142 or +977 981 234 5678.";

    private PhoneNumbers() {
    }

    /** @return the number in +&lt;country&gt;&lt;number&gt; form, or null when blank */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String raw = input.trim();
        if (!raw.matches("[0-9+() .-]+") || raw.lastIndexOf('+') > 0) {
            throw new BusinessRuleException(INVALID);
        }
        String digits = raw.replaceAll("[^0-9]", "");
        boolean international = raw.startsWith("+");
        if (!international && digits.startsWith("00")) {
            digits = digits.substring(2);
            international = true;
        }
        if (!international) {
            if (digits.length() == 10) {
                digits = "1" + digits;
            } else if (!(digits.length() == 11 && digits.startsWith("1"))) {
                throw new BusinessRuleException("Phone needs 10 digits for a US number, or a country code starting "
                        + "with + (e.g. +977 981 234 5678).");
            }
        }
        if (digits.length() < 8 || digits.length() > 15 || digits.startsWith("0")) {
            throw new BusinessRuleException(INVALID);
        }
        if (digits.startsWith("1")) {
            // US/Canada: 1 + 10 digits; area code and exchange can't start with 0 or 1.
            if (digits.length() != 11 || digits.charAt(1) < '2' || digits.charAt(4) < '2') {
                throw new BusinessRuleException(INVALID);
            }
        }
        return "+" + digits;
    }
}
