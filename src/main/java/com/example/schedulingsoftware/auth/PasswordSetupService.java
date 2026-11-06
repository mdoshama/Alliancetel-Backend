package com.example.schedulingsoftware.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.notify.EmailSettings;
import com.example.schedulingsoftware.notify.ScheduleNotifier;
import com.example.schedulingsoftware.notify.SmsSettings;
import com.example.schedulingsoftware.notify.WhatsAppSettings;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends people a one-time link to choose their own password: when an admin creates their account, and when an
 * admin resets their password. It goes by email, and by text when texting is connected and they agreed to texts
 * (or the admin confirms they agreed to get this link by text). The temporary password still works as a backup.
 */
@Slf4j
@Service
public class PasswordSetupService {

    static final String EXPIRED = "This link has expired or was already used. Ask your manager or an admin to send a "
            + "new one.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordSetupTokenRepository tokens;
    private final ScheduleNotifier notifier;
    private final WhatsAppSettings settings;
    private final EmailSettings emailSettings;
    private final SmsSettings smsSettings;
    private final Duration lifetime;

    public PasswordSetupService(PasswordSetupTokenRepository tokens, ScheduleNotifier notifier,
            WhatsAppSettings settings, EmailSettings emailSettings, SmsSettings smsSettings,
            @Value("${alliancetel.security.temporary-password-hours:168}") long hours) {
        this.tokens = tokens;
        this.notifier = notifier;
        this.settings = settings;
        this.emailSettings = emailSettings;
        this.smsSettings = smsSettings;
        this.lifetime = Duration.ofHours(hours);
    }

    /**
     * What happened to the link, for the admin's confirmation.
     *
     * @param emailed queued to their email and email is connected
     * @param texted  queued as a text message
     * @param note    why it wasn't sent, or null
     */
    public record LinkResult(boolean emailed, boolean texted, String note) {
        public static final LinkResult NONE = new LinkResult(false, false, null);
    }

    /** Creates a fresh link (older ones stop working) and queues the email and text. */
    @Transactional
    public LinkResult send(Employee e, boolean newAccount, boolean textToo) {
        if (!e.isActive()) {
            return LinkResult.NONE;
        }
        if (settings.getAppUrl().isEmpty()) {
            return new LinkResult(false, false,
                    "No set-password link was sent because the app's address (APP_URL) isn't set. Share the temporary password instead.");
        }
        tokens.deleteForEmployee(e.getId());
        tokens.deleteExpired(Instant.now());
        String secret = newSecret();
        PasswordSetupToken t = new PasswordSetupToken();
        t.setEmployee(e);
        t.setTokenHash(hash(secret));
        t.setNewAccount(newAccount);
        t.setExpiresAt(Instant.now().plus(lifetime));
        tokens.save(t);
        String link = settings.link("/set-password?t=" + secret);
        boolean texted = notifier.passwordLink(e, link, newAccount, days(), textToo);
        log.info("Set-password link sent to employee {} (email{})", e.getId(), texted ? " and text" : "");
        String note = null;
        if (!emailSettings.isConnected()) {
            note = "Email isn't connected yet, so the link was only recorded on the Messages page.";
        } else if (textToo && !texted) {
            note = e.getPhone() == null ? "There's no phone number on file, so no text was sent."
                    : !smsSettings.isConnected() ? "Text messaging isn't connected yet, so no text was sent." : null;
        }
        return new LinkResult(emailSettings.isConnected() && e.getEmail() != null, texted, note);
    }

    /** The person a link belongs to, or an error if it's unknown, used or expired. */
    @Transactional(readOnly = true)
    public PasswordSetupToken find(String secret) {
        PasswordSetupToken t = secret == null || secret.isBlank() ? null
                : tokens.findByTokenHash(hash(secret.trim())).orElse(null);
        if (t == null || t.getExpiresAt().isBefore(Instant.now()) || !t.getEmployee().isActive()) {
            throw new BusinessRuleException(EXPIRED);
        }
        return t;
    }

    /** After the password is set: every link this person had stops working. */
    @Transactional
    public void used(Employee e) {
        tokens.deleteForEmployee(e.getId());
    }

    private String days() {
        long hours = lifetime.toHours();
        return hours % 24 == 0 ? (hours / 24) + (hours == 24 ? " day" : " days") : hours + " hours";
    }

    /** 24 random bytes as 32 URL-safe characters: short enough for one text message, impossible to guess. */
    static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
