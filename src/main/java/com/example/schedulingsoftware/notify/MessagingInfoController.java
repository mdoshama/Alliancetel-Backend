package com.example.schedulingsoftware.notify;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** For everyone signed in: which update channels are on, and how to join the Twilio WhatsApp Sandbox if it's used. */
@RestController
@RequiredArgsConstructor
public class MessagingInfoController {

    /**
     * @param sandboxNumber the WhatsApp number to message (null when not using the Twilio Sandbox)
     * @param sandboxJoin   what to send it first, e.g. "join happy-tiger"
     */
    public record MessagingInfo(boolean whatsappConnected, String whatsappProvider, String sandboxNumber,
            String sandboxJoin, boolean smsConnected) {
    }

    private final WhatsAppSettings whatsApp;
    private final SmsSettings sms;

    @GetMapping("/api/auth/messaging")
    public MessagingInfo info() {
        String number = whatsApp.isConnected() ? whatsApp.getSandboxNumber() : null;
        String join = whatsApp.getTwilioSandboxJoin();
        // Join instructions only while sending from the Sandbox, and only if the join phrase is set.
        boolean showJoin = number != null && join != null && !join.isBlank();
        return new MessagingInfo(whatsApp.isConnected(), whatsApp.isTwilio() ? "twilio" : "meta",
                showJoin ? number : null, showJoin ? join : null, sms.isConnected());
    }
}
