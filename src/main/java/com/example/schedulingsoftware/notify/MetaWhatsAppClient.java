package com.example.schedulingsoftware.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * WhatsApp Cloud API (Meta): POST https://graph.facebook.com/{version}/{phone-number-id}/messages with a template
 * message. See https://developers.facebook.com/docs/whatsapp/cloud-api/guides/send-message-templates
 */
@Component
@RequiredArgsConstructor
public class MetaWhatsAppClient implements WhatsAppClient {

    private static final Pattern MESSAGE_ID = Pattern.compile("\"id\"\\s*:\\s*\"(wamid[^\"]*)\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ERROR_DETAILS = Pattern.compile("\"details\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final WhatsAppSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public SendResult send(String to, String template, String language, List<String> params) {
        StringBuilder parameters = new StringBuilder();
        for (String p : params) {
            if (!parameters.isEmpty()) {
                parameters.append(',');
            }
            parameters.append("{\"type\":\"text\",\"text\":").append(json(p)).append('}');
        }
        String body = "{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":"
                + json(to.replace("+", "")) + ",\"type\":\"template\",\"template\":{\"name\":" + json(template)
                + ",\"language\":{\"code\":" + json(language) + "},\"components\":[{\"type\":\"body\",\"parameters\":["
                + parameters + "]}]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.getApiBaseUrl() + "/" + settings.getApiVersion()
                        + "/" + settings.getPhoneNumberId() + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + settings.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 == 2) {
                Matcher id = MESSAGE_ID.matcher(text);
                return SendResult.sent(id.find() ? id.group(1) : null);
            }
            boolean retry = response.statusCode() == 429 || response.statusCode() >= 500;
            return SendResult.failed("WhatsApp said: " + errorText(text, response.statusCode()), retry);
        } catch (java.io.IOException e) {
            return SendResult.failed("Couldn't reach WhatsApp: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed("Interrupted while sending.", true);
        }
    }

    /** Meta's own explanation, e.g. "(#132001) Template name does not exist in the translation". */
    static String errorText(String body, int status) {
        Matcher m = ERROR_MESSAGE.matcher(body);
        String message = m.find() ? unescape(m.group(1)) : "HTTP " + status;
        Matcher d = ERROR_DETAILS.matcher(body);
        if (d.find()) {
            message += " – " + unescape(d.group(1));
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");
    }

    /** A JSON string literal. */
    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
