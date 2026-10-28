package com.example.schedulingsoftware.auth;

import java.security.SecureRandom;

import com.example.schedulingsoftware.common.BusinessRuleException;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    // No look-alike characters (0/O, 1/l/I) so temporary passwords are easy to read out.
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH
                || password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new BusinessRuleException(
                    "Password must be at least " + MIN_LENGTH + " characters and include a letter and a number.");
        }
    }

    /** A 10-character temporary password with at least one letter and one digit. */
    public static String generateTemporary() {
        StringBuilder sb = new StringBuilder();
        String all = LETTERS + DIGITS;
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        for (int i = 0; i < 8; i++) {
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
}
