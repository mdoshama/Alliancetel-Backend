package com.example.schedulingsoftware.notify;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Twilio's Messages API, shared by text messages and Twilio WhatsApp: POST /2010-04-01/Accounts/{sid}/Messages.json,
 * form-encoded, with the account SID and auth token as Basic auth. When APP_URL is set, Twilio reports what happened
 * to each message (delivered, failed and why) to {@link TwilioStatusController}.
 */
@Component
@RequiredArgsConstructor
public class TwilioApi {

    static final String STATUS_PATH = "/api/webhooks/twilio/status";

    private static final Pattern SID = Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");

    private final SmsSettings smsSettings;
    private final WhatsAppSettings whatsAppSettings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Where Twilio sends delivery reports, or null when APP_URL isn't set. */
    public String statusCallbackUrl() {
        String app = whatsAppSettings.getAppUrl();
        return app.isEmpty() ? null : app + STATUS_PATH;
    }

    SendResult send(Map<String, String> fields, String what) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        if (statusCallbackUrl() != null) {
            form.put("StatusCallback", statusCallbackUrl());
        }
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        String auth = Base64.getEncoder().encodeToString(
                (smsSettings.getAccountSid() + ":" + smsSettings.getAuthToken()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(smsSettings.getApiBaseUrl() + "/2010-04-01/Accounts/"
                        + enc(smsSettings.getAccountSid()) + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String reply = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 == 2) {
                Matcher sid = SID.matcher(reply);
                return SendResult.sent(sid.find() ? sid.group(1) : null);
            }
            boolean retry = response.statusCode() == 429 || response.statusCode() >= 500;
            return SendResult.failed(what + " said: " + errorText(reply, response.statusCode()), retry);
        } catch (java.io.IOException e) {
            return SendResult.failed("Couldn't reach the messaging service: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed("Interrupted while sending.", true);
        }
    }

    /** Twilio's explanation with its error code, e.g. "Attempt to send to unsubscribed recipient (21610)". */
    static String errorText(String body, int status) {
        Matcher m = MESSAGE.matcher(body);
        String message = m.find() ? m.group(1).replace("\\\"", "\"") : "HTTP " + status;
        Matcher c = CODE.matcher(body);
        if (c.find()) {
            message += " (" + c.group(1) + ")";
            String hint = TwilioErrors.hint(c.group(1));
            if (hint != null) {
                message += ". " + hint;
            }
        }
        // People see this on the Messages page: no provider names.
        message = message.replaceAll("(?i)twilio", "messaging service");
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
