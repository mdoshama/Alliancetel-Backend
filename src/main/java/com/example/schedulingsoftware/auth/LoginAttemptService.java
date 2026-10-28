package com.example.schedulingsoftware.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Slows down password guessing: after too many failed sign-ins for one email (or from one IP address) within the
 * lock window, further attempts are refused until the window passes. A successful sign-in clears the email's count.
 * Kept in memory, which is fine for a single backend instance.
 */
@Service
public class LoginAttemptService {

    private final int maxPerEmail;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(@Value("${alliancetel.security.max-failed-logins:5}") int maxFailedLogins,
            @Value("${alliancetel.security.login-lock-minutes:15}") long lockMinutes) {
        this(maxFailedLogins, lockMinutes, Clock.systemUTC());
    }

    LoginAttemptService(int maxFailedLogins, long lockMinutes, Clock clock) {
        this.maxPerEmail = maxFailedLogins;
        this.maxPerIp = maxFailedLogins * 4;
        this.window = Duration.ofMinutes(lockMinutes);
        this.clock = clock;
    }

    /** Throws if this email or IP address is temporarily locked. */
    public void checkAllowed(String email, String ip) {
        if (recent(emailKey(email)) >= maxPerEmail || recent(ipKey(ip)) >= maxPerIp) {
            throw new TooManyAttemptsException("Too many failed sign-in attempts. Try again in "
                    + window.toMinutes() + " minutes or ask an administrator to reset your password.");
        }
    }

    public void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        failures.computeIfAbsent(emailKey(email), k -> new ArrayDeque<>()).addLast(now);
        failures.computeIfAbsent(ipKey(ip), k -> new ArrayDeque<>()).addLast(now);
    }

    public void recordSuccess(String email) {
        failures.remove(emailKey(email));
    }

    private int recent(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return 0;
        }
        synchronized (attempts) {
            Instant cutoff = clock.instant().minus(window);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.pollFirst();
            }
            return attempts.size();
        }
    }

    private static String emailKey(String email) {
        return "e:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "i:" + (ip == null ? "" : ip);
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String message) {
            super(message);
        }
    }
}
