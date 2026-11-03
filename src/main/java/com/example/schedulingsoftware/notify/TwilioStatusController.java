package com.example.schedulingsoftware.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivery reports from Twilio (texts and Twilio WhatsApp). Twilio accepts a message first and reports a few seconds
 * later whether it was delivered or failed (e.g. not on the WhatsApp Sandbox, carrier filtered). A failure turns the
 * message to Failed on the Messages page, with the reason. Only reports signed with the account's auth token count.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TwilioStatusController {

    private final NotificationRepository repository;
    private final TwilioApi api;
    private final SmsSettings smsSettings;

    @PostMapping(TwilioApi.STATUS_PATH)
    @Transactional
    public ResponseEntity<Void> status(@RequestParam Map<String, String> form,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature) {
        String url = api.statusCallbackUrl();
        if (url == null || smsSettings.getAuthToken().isEmpty()
                || !validSignature(smsSettings.getAuthToken(), url, form, signature)) {
            return ResponseEntity.status(403).build();
        }
        String sid = form.get("MessageSid");
        String status = form.getOrDefault("MessageStatus", "");
        if (sid == null) {
            return ResponseEntity.noContent().build();
        }
        repository.findFirstByProviderMessageId(sid).ifPresent(n -> {
            if ("failed".equals(status) || "undelivered".equals(status)) {
                String code = form.get("ErrorCode");
                String hint = TwilioErrors.hint(code);
                n.setStatus(NotificationStatus.FAILED);
                n.setError(("Not delivered" + (code == null ? "" : " (" + code + ")")
                        + (hint == null ? "." : ": " + hint)));
                log.info("Twilio message {} {}: {}", sid, status, code);
            } else if ("delivered".equals(status) || "read".equals(status)) {
                n.setError(null);
            }
        });
        return ResponseEntity.noContent().build();
    }

    /** Twilio's signature: Base64(HMAC-SHA1(auth token, URL + each form key and value, sorted by key)). */
    static boolean validSignature(String authToken, String url, Map<String, String> form, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        StringBuilder data = new StringBuilder(url);
        new TreeMap<>(form).forEach((k, v) -> data.append(k).append(v));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
