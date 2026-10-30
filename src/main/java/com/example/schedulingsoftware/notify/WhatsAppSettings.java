package com.example.schedulingsoftware.notify;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * WhatsApp Cloud API settings, all from environment variables (see README, "WhatsApp messages"). Until
 * WHATSAPP_ENABLED, WHATSAPP_PHONE_NUMBER_ID and WHATSAPP_ACCESS_TOKEN are set, messages are recorded but not sent.
 */
@Component
@Getter
public class WhatsAppSettings {

    private final boolean enabled;
    private final String phoneNumberId;
    private final String accessToken;
    private final String apiVersion;
    private final String language;
    private final String appUrl;
    /** Meta's API address; only changed for testing. */
    private final String apiBaseUrl;
    private final Map<NotificationKind, String> templates;

    public WhatsAppSettings(
            @Value("${alliancetel.whatsapp.enabled:false}") boolean enabled,
            @Value("${alliancetel.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${alliancetel.whatsapp.access-token:}") String accessToken,
            @Value("${alliancetel.whatsapp.api-version:v22.0}") String apiVersion,
            @Value("${alliancetel.whatsapp.language:en_US}") String language,
            @Value("${alliancetel.app-url:}") String appUrl,
            @Value("${alliancetel.whatsapp.template.week-published:schedule_published}") String weekPublished,
            @Value("${alliancetel.whatsapp.template.week-changed:schedule_changed}") String weekChanged,
            @Value("${alliancetel.whatsapp.template.day-off:day_off_update}") String dayOff,
            @Value("${alliancetel.whatsapp.template.announcement:team_announcement}") String announcement,
            @Value("${alliancetel.whatsapp.api-base-url:https://graph.facebook.com}") String apiBaseUrl) {
        this.enabled = enabled;
        this.phoneNumberId = phoneNumberId.trim();
        this.accessToken = accessToken.trim();
        this.apiVersion = apiVersion.trim();
        this.language = language.trim();
        this.appUrl = appUrl.trim().replaceAll("/+$", "");
        this.apiBaseUrl = apiBaseUrl.trim().replaceAll("/+$", "");
        this.templates = Map.of(NotificationKind.WEEK_PUBLISHED, weekPublished.trim(),
                NotificationKind.WEEK_CHANGED, weekChanged.trim(),
                NotificationKind.DAY_OFF_DECISION, dayOff.trim(),
                NotificationKind.ANNOUNCEMENT, announcement.trim());
    }

    /** Ready to send: switched on and both credentials present. */
    public boolean isConnected() {
        return enabled && !phoneNumberId.isEmpty() && !accessToken.isEmpty();
    }

    public String template(NotificationKind kind) {
        return templates.get(kind);
    }

    /** Where to see the schedule, for the end of each message. */
    public String link(String path) {
        return appUrl.isEmpty() ? "the AllianceTel app" : appUrl + path;
    }
}
