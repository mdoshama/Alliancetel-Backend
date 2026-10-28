package com.example.schedulingsoftware.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService attempts = new LoginAttemptService(3, 15, clock);

    @Test
    void locksAnEmailAfterTooManyFailuresAndUnlocksAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            attempts.checkAllowed("Sam@Example.com", "1.1.1.1");
            attempts.recordFailure("sam@example.com", "1.1.1.1");
        }
        assertThrows(LoginAttemptService.TooManyAttemptsException.class,
                () -> attempts.checkAllowed("sam@example.com", "2.2.2.2"));

        clock.advance(Duration.ofMinutes(16));
        assertDoesNotThrow(() -> attempts.checkAllowed("sam@example.com", "1.1.1.1"));
    }

    @Test
    void successClearsTheEmailCount() {
        attempts.recordFailure("sam@example.com", "1.1.1.1");
        attempts.recordFailure("sam@example.com", "1.1.1.1");
        attempts.recordSuccess("sam@example.com");
        attempts.recordFailure("sam@example.com", "1.1.1.1");
        assertDoesNotThrow(() -> attempts.checkAllowed("sam@example.com", "1.1.1.1"));
    }

    @Test
    void oneAddressTryingManyEmailsIsAlsoLocked() {
        for (int i = 0; i < 12; i++) {
            attempts.recordFailure("user" + i + "@example.com", "9.9.9.9");
        }
        assertThrows(LoginAttemptService.TooManyAttemptsException.class,
                () -> attempts.checkAllowed("someone-new@example.com", "9.9.9.9"));
        assertDoesNotThrow(() -> attempts.checkAllowed("someone-new@example.com", "8.8.8.8"));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
