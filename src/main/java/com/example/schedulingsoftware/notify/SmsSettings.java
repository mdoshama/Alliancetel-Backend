package com.example.schedulingsoftware.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * Text message (SMS) settings for Twilio, all from environment variables (see README, "Text messages"). Until
 * SMS_ENABLED, TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and a sender (TWILIO_FROM_NUMBER or
 * TWILIO_MESSAGING_SERVICE_SID) are set, text messages are recorded on the Messages page but not sent.
 */
@Component
@Getter
public class SmsSettings {

    private final boolean enabled;
    private final String accountSid;
    private final String authToken;
    /** The Twilio number texts come from, e.g. +16195550100. */
    private final String fromNumber;
    /** A Twilio Messaging Service (preferred for US A2P 10DLC); used instead of the number when set. */
    private final String messagingServiceSid;
    /** Twilio's API address; only changed for testing. */
    private final String apiBaseUrl;

    public SmsSettings(
            @Value("${alliancetel.sms.enabled:false}") boolean enabled,
            @Value("${alliancetel.sms.twilio.account-sid:}") String accountSid,
            @Value("${alliancetel.sms.twilio.auth-token:}") String authToken,
            @Value("${alliancetel.sms.twilio.from-number:}") String fromNumber,
            @Value("${alliancetel.sms.twilio.messaging-service-sid:}") String messagingServiceSid,
            @Value("${alliancetel.sms.twilio.api-base-url:https://api.twilio.com}") String apiBaseUrl) {
        this.enabled = enabled;
        this.accountSid = accountSid.trim();
        this.authToken = authToken.trim();
        this.fromNumber = fromNumber.trim();
        this.messagingServiceSid = messagingServiceSid.trim();
        this.apiBaseUrl = apiBaseUrl.trim().replaceAll("/+$", "");
    }

    /** Ready to send: switched on, credentials present, and somewhere to send from. */
    public boolean isConnected() {
        return enabled && !accountSid.isEmpty() && !authToken.isEmpty()
                && (!fromNumber.isEmpty() || !messagingServiceSid.isEmpty());
    }

    /** What the Messages page shows as the sender. */
    public String getSender() {
        return !messagingServiceSid.isEmpty() ? "Messaging Service " + messagingServiceSid : fromNumber;
    }
}
