package com.example.schedulingsoftware.notify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Text messages through Twilio. Twilio handles STOP / HELP replies itself and won't text people who replied STOP. */
@Component
@RequiredArgsConstructor
public class TwilioSmsClient implements SmsClient {

    private final SmsSettings settings;
    private final TwilioApi api;

    @Override
    public SendResult send(String to, String text) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", to);
        form.put("Body", text);
        if (!settings.getMessagingServiceSid().isEmpty()) {
            form.put("MessagingServiceSid", settings.getMessagingServiceSid());
        } else {
            form.put("From", settings.getFromNumber());
        }
        return api.send(form, "Text service");
    }

    static String errorText(String body, int status) {
        return TwilioApi.errorText(body, status);
    }
}
