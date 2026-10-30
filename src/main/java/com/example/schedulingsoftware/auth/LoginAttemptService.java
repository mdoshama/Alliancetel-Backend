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

import lombok.extern.slf4j.Slf4j;

/**
 * Slows down password guessing: after too many failed sign-ins for one email (or from one IP address) within the
 * lock window, further attempts are refused until the window passes. A successful sign-in clears the email's count.
 * Kept in memory, which is fine for a single backend instance.
 */
@Slf4j
@Service
public class LoginAttemptService {

    private final int maxPerEmail;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private static final int CLEANUP_THRESHOLD = 10_000;

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
        add(emailKey(email), now);
        add(ipKey(ip), now);
        if (recent(emailKey(email)) == maxPerEmail) {
            log.warn("Sign-in locked for {} minutes after {} failed attempts: {} (last from {})",
                    window.toMinutes(), maxPerEmail, email == null ? "" : email.trim(), ip);
        }
        if (recent(ipKey(ip)) == maxPerIp) {
            log.warn("Sign-in locked for {} minutes for address {} after {} failed attempts",
                    window.toMinutes(), ip, maxPerIp);
        }
        if (failures.size() > CLEANUP_THRESHOLD) {
            forgetOldFailures();
        }
    }

    private void add(String key, Instant when) {
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(when);
        }
    }

    /**
     * Drops entries whose failures are all older than the window, so guessing with many made-up emails can't
     * fill the server's memory.
     */
    void forgetOldFailures() {
        failures.keySet().removeIf(key -> recent(key) == 0);
    }

    int trackedKeys() {
        return failures.size();
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
