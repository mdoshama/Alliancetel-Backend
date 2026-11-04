package com.example.schedulingsoftware.notify;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * WhatsApp settings: Meta's WhatsApp Cloud API (default) or Twilio's WhatsApp (WHATSAPP_PROVIDER=twilio, sharing the
 * Twilio account used for text messages). Meta Cloud API settings, all from environment variables (see README, "WhatsApp messages"). Until
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
    /** "meta" (WhatsApp Cloud API, the default) or "twilio" (Twilio's WhatsApp, including its Sandbox). */
    private final String provider;
    /** Twilio: the WhatsApp sender, e.g. whatsapp:+14155238886 (the Sandbox number). */
    private final String twilioFrom;
    /** Twilio Sandbox: the join phrase people send first, e.g. "join happy-tiger". Empty outside the Sandbox. */
    private final String twilioSandboxJoin;
    /** Twilio: approved Content template SIDs (HX…) per message kind, for messages outside the 24-hour window. */
    private final Map<NotificationKind, String> twilioContentSids;
    private final String twilioAccountSid;
    private final String twilioAuthToken;

    /** Meta only (kept for tests and older setups). */
    public WhatsAppSettings(boolean enabled, String phoneNumberId, String accessToken, String apiVersion,
            String language, String appUrl, String weekPublished, String weekChanged, String dayOff,
            String announcement, String apiBaseUrl) {
        this(enabled, phoneNumberId, accessToken, apiVersion, language, appUrl, weekPublished, weekChanged, dayOff,
                announcement, apiBaseUrl, "meta", "", "", "", "", "", "", "", "");
    }

    @org.springframework.beans.factory.annotation.Autowired
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
            @Value("${alliancetel.whatsapp.api-base-url:https://graph.facebook.com}") String apiBaseUrl,
            @Value("${alliancetel.whatsapp.provider:meta}") String provider,
            @Value("${alliancetel.whatsapp.twilio.from:whatsapp:+14155238886}") String twilioFrom,
            @Value("${alliancetel.whatsapp.twilio.sandbox-join:}") String twilioSandboxJoin,
            @Value("${alliancetel.sms.twilio.account-sid:}") String twilioAccountSid,
            @Value("${alliancetel.sms.twilio.auth-token:}") String twilioAuthToken,
            @Value("${alliancetel.whatsapp.twilio.content.week-published:}") String sidWeekPublished,
            @Value("${alliancetel.whatsapp.twilio.content.week-changed:}") String sidWeekChanged,
            @Value("${alliancetel.whatsapp.twilio.content.day-off:}") String sidDayOff,
            @Value("${alliancetel.whatsapp.twilio.content.announcement:}") String sidAnnouncement) {
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
        this.provider = provider.trim().toLowerCase(java.util.Locale.ROOT);
        String from = twilioFrom.trim();
        this.twilioFrom = from.isEmpty() || from.startsWith("whatsapp:") ? from : "whatsapp:" + from;
        this.twilioSandboxJoin = twilioSandboxJoin.trim();
        this.twilioAccountSid = twilioAccountSid.trim();
        this.twilioAuthToken = twilioAuthToken.trim();
        this.twilioContentSids = Map.of(NotificationKind.WEEK_PUBLISHED, sidWeekPublished.trim(),
                NotificationKind.WEEK_CHANGED, sidWeekChanged.trim(),
                NotificationKind.DAY_OFF_DECISION, sidDayOff.trim(),
                NotificationKind.ANNOUNCEMENT, sidAnnouncement.trim());
    }

    public boolean isTwilio() {
        return "twilio".equals(provider);
    }

    /** The Sandbox number people send the join phrase to, e.g. +14155238886 (null unless using the Sandbox). */
    public String getSandboxNumber() {
        return isSandbox() ? SANDBOX_NUMBER : null;
    }

    /** Twilio's shared WhatsApp Sandbox number (testing only). */
    public static final String SANDBOX_NUMBER = "+14155238886";

    /** Sending from Twilio's Sandbox (the default sender) rather than your own registered WhatsApp number. */
    public boolean isSandbox() {
        return isTwilio() && SANDBOX_NUMBER.equals(twilioFrom.replace("whatsapp:", ""));
    }

    /** The number WhatsApp messages come from, e.g. +16195550100 (Twilio only). */
    public String getSenderNumber() {
        return isTwilio() ? twilioFrom.replace("whatsapp:", "") : null;
    }

    /** All four approved Twilio Content templates are set (needed outside the 24-hour window on your own number). */
    public boolean hasAllContentTemplates() {
        return twilioContentSids.values().stream().noneMatch(String::isEmpty);
    }

    /** The approved Twilio Content template for this kind of message, or "" to send it as plain text. */
    public String contentSid(NotificationKind kind) {
        return twilioContentSids.getOrDefault(kind, "");
    }

    /** Which kind of message a stored template name belongs to. */
    public NotificationKind kindOf(String template) {
        return templates.entrySet().stream().filter(e -> e.getValue().equals(template)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    /** Ready to send: switched on and both credentials present. */
    public boolean isConnected() {
        if (isTwilio()) {
            return enabled && !twilioAccountSid.isEmpty() && !twilioAuthToken.isEmpty() && !twilioFrom.isEmpty();
        }
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
