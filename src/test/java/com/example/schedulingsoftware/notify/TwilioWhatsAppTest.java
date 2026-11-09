package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/** Twilio WhatsApp settings, template values, and checking Twilio's signature on delivery reports. */
class TwilioWhatsAppTest {

    private static WhatsAppSettings twilio(String join, String contentSid) {
        return twilio("+14155238886", join, contentSid, "");
    }

    private static WhatsAppSettings twilio(String from, String join, String contentSid, String others) {
        return new WhatsAppSettings(true, "", "", "v22.0", "en_US", "https://atdirect.app", "schedule_published",
                "schedule_changed", "day_off_update", "team_announcement", "https://graph.facebook.com", "twilio",
                from, join, "ACtest", "token", contentSid, others, others, others);
    }

    @Test
    void twilioWhatsAppIsConnectedWithTheTwilioAccount() {
        WhatsAppSettings s = twilio("join happy-tiger", "");
        assertTrue(s.isTwilio());
        assertTrue(s.isConnected(), "no Meta phone number ID or token needed");
        assertEquals("whatsapp:+14155238886", s.getTwilioFrom());
        assertEquals("+14155238886", s.getSandboxNumber());
        assertEquals(NotificationKind.DAY_OFF_DECISION, s.kindOf("day_off_update"));
        assertTrue(twilio("", "").isSandbox(), "the default sender is the Sandbox, even without a join phrase");
        WhatsAppSettings own = twilio("whatsapp:+16195550100", "join happy-tiger", "HX1", "HX2");
        assertFalse(own.isSandbox(), "a registered number is not the Sandbox, even if the join phrase is left set");
        assertNull(own.getSandboxNumber());
        assertEquals("+16195550100", own.getSenderNumber());
        assertTrue(own.hasAllContentTemplates());
        assertFalse(twilio("whatsapp:+16195550100", "", "HX1", "").hasAllContentTemplates());
        assertEquals("HX123", twilio("", "HX123").contentSid(NotificationKind.WEEK_PUBLISHED));
        assertFalse(new WhatsAppSettings(true, "", "", "v22.0", "en_US", "", "a", "b", "c", "d", "x").isConnected(),
                "Meta still needs its own settings");
    }

    @Test
    void templateValuesAreNumberedJson() {
        assertEquals("{\"1\":\"Ana\",\"2\":\"Say \\\"hi\\\"\"}", TwilioWhatsAppClient.contentVariables(List.of("Ana", "Say \"hi\"")));
    }

    @Test
    void onlyReportsSignedWithTheAuthTokenAreTrusted() throws Exception {
        String url = "https://atdirect.app/api/webhooks/twilio/status";
        Map<String, String> form = Map.of("MessageSid", "SM1", "MessageStatus", "undelivered", "ErrorCode", "63015");
        StringBuilder data = new StringBuilder(url);
        new TreeMap<>(form).forEach((k, v) -> data.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("token".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String good = Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));

        assertTrue(TwilioStatusController.validSignature("token", url, form, good));
        assertFalse(TwilioStatusController.validSignature("other", url, form, good));
        assertFalse(TwilioStatusController.validSignature("token", url, form, null));
        assertEquals("They haven't joined the WhatsApp test sandbox yet: they need to send the join phrase first.",
                TwilioErrors.hint("63015"));
    }
}
