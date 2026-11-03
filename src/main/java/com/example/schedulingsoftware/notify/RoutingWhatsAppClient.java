package com.example.schedulingsoftware.notify;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Sends WhatsApp through whichever provider is set: Meta's Cloud API (default) or Twilio. */
@Component
@Primary
@RequiredArgsConstructor
public class RoutingWhatsAppClient implements WhatsAppClient {

    private final WhatsAppSettings settings;
    private final MetaWhatsAppClient meta;
    private final TwilioWhatsAppClient twilio;

    @Override
    public SendResult send(String to, String template, String language, List<String> params) {
        return settings.isTwilio() ? twilio.send(to, template, language, params)
                : meta.send(to, template, language, params);
    }
}
