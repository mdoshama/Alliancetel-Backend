package com.example.schedulingsoftware.notify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * WhatsApp through Twilio (WHATSAPP_PROVIDER=twilio). With an approved Twilio Content template for the kind of
 * message (TWILIO_WA_CONTENT_…), it sends that template with the values; otherwise it sends the text as a plain
 * message, which WhatsApp only delivers within 24 hours of the person last messaging you. That's how the Twilio
 * Sandbox works: joining (and any message after) opens the 24 hours.
 */
@Component
@RequiredArgsConstructor
public class TwilioWhatsAppClient implements WhatsAppClient {

    private final WhatsAppSettings settings;
    private final TwilioApi api;

    @Override
    public SendResult send(String to, String template, String language, List<String> params) {
        NotificationKind kind = settings.kindOf(template);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", "whatsapp:" + to);
        form.put("From", settings.getTwilioFrom());
        String contentSid = kind == null ? "" : settings.contentSid(kind);
        if (!contentSid.isEmpty()) {
            form.put("ContentSid", contentSid);
            form.put("ContentVariables", contentVariables(params));
        } else {
            String text = kind == null ? String.join(" ", params)
                    : ScheduleNotifier.fill(ScheduleNotifier.TEMPLATE_TEXT.get(kind), params);
            form.put("Body", text);
        }
        return api.send(form, "WhatsApp (Twilio)");
    }

    /** {"1":"Ana","2":"Nov 9–15",…} */
    static String contentVariables(List<String> params) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < params.size(); i++) {
            json.append(i == 0 ? "" : ",").append('"').append(i + 1).append("\":")
                    .append(MetaWhatsAppClient.json(params.get(i)));
        }
        return json.append('}').toString();
    }
}
