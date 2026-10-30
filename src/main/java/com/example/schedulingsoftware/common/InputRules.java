package com.example.schedulingsoftware.common;

/**
 * Allowed characters for each kind of text field, used with {@code @Pattern} on request bodies. The web app uses the
 * same rules (src/app/core/validation.ts) so people see the problem before saving. Leading and trailing spaces are
 * allowed here and trimmed when saving. A blank value passes these rules; {@code @NotBlank} says when one is required.
 */
public final class InputRules {

    /** First and last names: letters (any language), spaces, hyphens, apostrophes and periods. */
    public static final String PERSON_NAME = "^\\s*$|^\\s*[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]*$";
    public static final String PERSON_NAME_MSG = "may only contain letters, spaces, hyphens, apostrophes and periods";

    /** Employee and store codes: letters, numbers, hyphen and underscore, no spaces. */
    public static final String CODE = "^\\s*$|^\\s*[A-Za-z0-9][A-Za-z0-9_-]*\\s*$";
    public static final String CODE_MSG = "may only contain letters, numbers, - and _ (no spaces)";

    /** A normal email address with a domain such as example.com. */
    public static final String EMAIL = "^\\s*$|^\\s*[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\s*$";
    public static final String EMAIL_MSG = "must be a valid email like name@example.com";

    /** Area and store names: letters, numbers, spaces and a little punctuation. */
    public static final String PLACE_NAME = "^\\s*$|^\\s*[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} &.,'’()/#-]*$";
    public static final String PLACE_NAME_MSG = "may only contain letters, numbers, spaces and & . , ' ( ) / # -";

    /** Free text (addresses, reasons, notes): anything except angle brackets and control characters. */
    public static final String SAFE_TEXT = "^[^<>\\p{Cntrl}]*$";
    public static final String SAFE_TEXT_MSG = "can't contain < or > or line breaks";

    /** Phone numbers as typed: digits, spaces, + ( ) - and periods. Checked properly by {@link PhoneNumbers}. */
    public static final String PHONE_CHARS = "^[0-9+() .-]*$";
    public static final String PHONE_CHARS_MSG = "may only contain digits, spaces and + ( ) -";

    private InputRules() {
    }

    /** Trimmed text, or null when blank. */
    public static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim().replaceAll("\\s{2,}", " ");
    }
}
