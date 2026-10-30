package com.example.schedulingsoftware.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Resend (resend.com): POST https://api.resend.com/emails with a Bearer API key. */
@Component
@RequiredArgsConstructor
public class ResendEmailClient implements EmailClient {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final EmailSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public SendResult send(String to, String subject, String text, String html, String idempotencyKey) {
        String json = MetaWhatsAppClient.json(to);
        StringBuilder body = new StringBuilder("{\"from\":").append(MetaWhatsAppClient.json(settings.getFrom()))
                .append(",\"to\":[").append(json).append("]")
                .append(",\"subject\":").append(MetaWhatsAppClient.json(subject))
                .append(",\"text\":").append(MetaWhatsAppClient.json(text))
                .append(",\"html\":").append(MetaWhatsAppClient.json(html));
        if (!settings.getReplyTo().isEmpty()) {
            body.append(",\"reply_to\":").append(MetaWhatsAppClient.json(settings.getReplyTo()));
        }
        body.append('}');
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.getApiBaseUrl() + "/emails"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + settings.getApiKey())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String reply = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 == 2) {
                Matcher id = ID.matcher(reply);
                return SendResult.sent(id.find() ? id.group(1) : null);
            }
            boolean retry = response.statusCode() == 429 || response.statusCode() >= 500;
            return SendResult.failed("Email service said: " + errorText(reply, response.statusCode()), retry);
        } catch (java.io.IOException e) {
            return SendResult.failed("Couldn't reach the email service: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed("Interrupted while sending.", true);
        }
    }

    /** Resend's explanation, e.g. "The example.com domain is not verified." */
    static String errorText(String body, int status) {
        Matcher m = MESSAGE.matcher(body);
        String message = m.find() ? m.group(1).replace("\\\"", "\"") : "HTTP " + status;
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
