package com.example.schedulingsoftware.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * Email through Resend's HTTPS API (Railway blocks ordinary SMTP mail on its Free and Hobby plans). Until
 * EMAIL_ENABLED, RESEND_API_KEY and EMAIL_FROM are set, emails are only recorded on the Messages page.
 */
@Component
@Getter
public class EmailSettings {

    private final boolean enabled;
    private final String apiKey;
    private final String from;
    private final String replyTo;
    /** Resend's API address; only changed for testing. */
    private final String apiBaseUrl;

    public EmailSettings(
            @Value("${alliancetel.email.enabled:false}") boolean enabled,
            @Value("${alliancetel.email.resend-api-key:}") String apiKey,
            @Value("${alliancetel.email.from:}") String from,
            @Value("${alliancetel.email.reply-to:}") String replyTo,
            @Value("${alliancetel.email.api-base-url:https://api.resend.com}") String apiBaseUrl) {
        this.enabled = enabled;
        this.apiKey = apiKey.trim();
        this.from = from.trim();
        this.replyTo = replyTo.trim();
        this.apiBaseUrl = apiBaseUrl.trim().replaceAll("/+$", "");
    }

    /** Ready to send: switched on, with an API key and a sender address. */
    public boolean isConnected() {
        return enabled && !apiKey.isEmpty() && !from.isEmpty();
    }
}
