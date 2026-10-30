package com.example.schedulingsoftware.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

import com.example.schedulingsoftware.common.BusinessRuleException;

/**
 * Rules for passwords people choose themselves. Kept readable for store staff: long enough, a letter and a number,
 * and not something an attacker would try first (common words, the company name, your own name or email).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    /** BCrypt only looks at the first 72 bytes, so longer passwords would silently lose their ending. */
    public static final int MAX_LENGTH = 64;
    private static final int MAX_BYTES = 72;

    /** The first admin password from ADMIN_PASSWORD only has to pass the basic rule; it must be changed at first sign-in. */
    public static final int BOOTSTRAP_MIN_LENGTH = 8;

    /**
     * Words that make up most guessed passwords. A password whose letters are only one of these (whatever digits or
     * symbols are added, e.g. "Welcome2026!") is refused.
     */
    private static final Set<String> COMMON_WORDS = Set.of(
            "password", "passw", "pass", "pwd", "welcome", "qwerty", "qwertyuiop", "asdf", "asdfgh", "zxcvbn",
            "letmein", "admin", "administrator", "changeme", "change", "iloveyou", "love", "abc", "abcd", "abcde",
            "abcdef", "monkey", "dragon", "sunshine", "football", "baseball", "soccer", "basketball", "princess",
            "master", "login", "hello", "freedom", "whatever", "trustno", "starwars", "shadow", "superman", "batman",
            "summer", "winter", "spring", "autumn", "fall", "secret", "test", "testing", "guest", "user", "default",
            "temp", "temporary", "alliancetel", "alliance", "tel", "metro", "tmobile", "mobile", "employee",
            "manager", "schedule", "scheduling", "store", "company", "work", "office", "january", "february",
            "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "q", "qw", "qwe", "a", "aa",
            "aaa", "x", "xx", "xxx", "z", "zz", "zzz", "qazwsx", "zaq", "iloveu", "michael", "jordan", "jesus",
            "ninja", "mustang", "charlie", "pokemon", "computer", "internet", "samsung", "apple", "iphone",
            "google", "facebook", "instagram");

    // No look-alike characters (0/O, 1/l/I) so temporary passwords are easy to read out.
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final int TEMPORARY_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    /** One sentence for the sign-in pages. */
    public static String summary() {
        return "Use at least " + MIN_LENGTH + " characters with a letter and a number. Avoid common words, "
                + "the company name and your own name or email.";
    }

    /**
     * Checks a password someone is choosing. {@code personal} holds their first name, last name and email,
     * none of which may appear in the password.
     */
    public static void validate(String password, String... personal) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BusinessRuleException("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessRuleException("Password can be at most " + MAX_LENGTH + " characters.");
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            throw new BusinessRuleException("Password must include at least one letter and one number.");
        }
        if (password.isBlank() || !password.equals(password.strip())) {
            throw new BusinessRuleException("Password can't start or end with a space.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.chars().distinct().count() < 5) {
            throw new BusinessRuleException("Password repeats the same few characters. Mix in more different ones.");
        }
        String lettersOnly = lower.replaceAll("[^\\p{L}]", "");
        if (COMMON_WORDS.contains(lettersOnly) || COMMON_WORDS.contains(unLeet(lower)) || isSequence(lower.replaceAll("[^\\p{L}\\p{N}]", ""))) {
            throw new BusinessRuleException("That password is too easy to guess. Avoid common words and patterns "
                    + "like \"Welcome123\" or \"abcd1234\".");
        }
        for (String part : personalParts(personal)) {
            if (lower.contains(part)) {
                throw new BusinessRuleException("Password can't contain your name or email.");
            }
        }
    }

    /** The basic rule only: used for the one-time ADMIN_PASSWORD, which must be changed at first sign-in. */
    public static void validateBootstrap(String password) {
        if (password == null || password.length() < BOOTSTRAP_MIN_LENGTH
                || password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new BusinessRuleException("Password must be at least " + BOOTSTRAP_MIN_LENGTH
                    + " characters and include a letter and a number.");
        }
    }

    /** A 12-character temporary password (about 70 bits of randomness) with at least one letter and one digit. */
    public static String generateTemporary() {
        StringBuilder sb = new StringBuilder();
        String all = LETTERS + DIGITS;
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        for (int i = 2; i < TEMPORARY_LENGTH; i++) {
            sb.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        // Shuffle so the letter/digit are not always in front.
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    /** "P@ssw0rd2026!" → "password": drops digits/symbols at the ends, then reads look-alike characters as letters. */
    private static String unLeet(String lower) {
        String core = lower.replaceAll("^[^\\p{L}@$]+", "").replaceAll("[^\\p{L}]+$", "");
        return core.replace('@', 'a').replace('$', 's').replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('7', 't').replace('!', 'i')
                .replaceAll("[^\\p{L}]", "");
    }

    /** "abcdefgh12", "12345678ab", "qwerty1234": letters or digits that just run along the alphabet or keyboard. */
    private static boolean isSequence(String s) {
        String letters = s.replaceAll("[^a-z]", "");
        String digits = s.replaceAll("[^0-9]", "");
        return runs(letters) && runs(digits);
    }

    private static boolean runs(String s) {
        if (s.length() < 3) {
            return true;
        }
        if ("qwertyuiopasdfghjklzxcvbnm".contains(s) || "1234567890".contains(s) || "0987654321".contains(s)) {
            return true;
        }
        int step = s.charAt(1) - s.charAt(0);
        if (Math.abs(step) > 1) {
            return false;
        }
        for (int i = 2; i < s.length(); i++) {
            if (s.charAt(i) - s.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    /** Name parts and the email's user part, at least 3 letters long, lower case. */
    private static Set<String> personalParts(String... personal) {
        java.util.Set<String> parts = new java.util.HashSet<>();
        for (String value : personal) {
            if (value == null) {
                continue;
            }
            String v = value.toLowerCase(Locale.ROOT);
            int at = v.indexOf('@');
            if (at >= 0) {
                v = v.substring(0, at);
            }
            for (String piece : v.split("[^\\p{L}\\p{N}]+")) {
                if (piece.length() >= 3) {
                    parts.add(piece);
                }
            }
        }
        return parts;
    }
}
