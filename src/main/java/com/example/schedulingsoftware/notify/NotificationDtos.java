package com.example.schedulingsoftware.notify;

import java.time.Instant;
import java.util.List;

import com.example.schedulingsoftware.common.InputRules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(Long id, Long employeeId, String employeeName, NotificationChannel channel,
            String phone, String email, String subject, NotificationKind kind, NotificationStatus status, String preview,
            String error, int attempts, Instant createdAt, Instant sentAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getEmployee() == null ? null : n.getEmployee().getId(),
                    n.getEmployee() == null ? null : n.getEmployee().getFullName(), n.getChannel(), n.getPhone(),
                    n.getEmail(), n.getSubject(), n.getKind(), n.getStatus(),
                    n.getChannel() == NotificationChannel.EMAIL && n.getBody() != null
                            && n.getKind() != NotificationKind.ACCOUNT_SETUP ? n.getBody() : n.getPreview(),
                    withoutProvider(n.getError()), n.getAttempts(), n.getCreatedAt(), n.getSentAt());
        }
    }

    /** A template to create in WhatsApp Manager. */
    public record TemplateInfo(NotificationKind kind, String name, String body) {
    }

    /**
     * @param connected messages are being sent (otherwise only recorded)
     * @param queued    waiting to be sent; failed and notSent are counts in the user's area (all areas for admins)
     */
    public record WhatsAppStatus(boolean connected, String apiVersion, String language, boolean appUrlSet,
            List<TemplateInfo> templates, long queued, long failed, long notSent, long withoutPhone, long notAgreed,
            /** Email: connected, the sender address, and how many people turned email off. */
            boolean emailConnected, String emailFrom, long emailOff,
            /** Text messages: connected, who they come from, and people with a phone who haven't agreed yet. */
            boolean smsConnected, String smsFrom, long smsNotAgreed,
            /** WhatsApp through "meta" or "twilio", and the Twilio Sandbox number and join phrase, if used. */
            String whatsappProvider, String sandboxNumber, String sandboxJoin,
            /** Twilio: the number WhatsApp comes from, and whether all four approved templates are set. */
            String whatsappFrom, boolean whatsappTemplatesReady) {
    }

    public record AnnounceRequest(
            @NotNull Long areaId,
            @NotBlank @Size(max = 500) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG)
            String message) {
    }

    /** A manager's or admin's message to one person (e.g. someone who hasn't picked their day off yet). */
    public record DirectMessageRequest(
            @NotNull Long employeeId,
            @NotBlank @Size(max = 500) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG)
            String message) {
    }

    /**
     * One message to several people (e.g. everyone who hasn't picked their day off). "{first}" in the text becomes
     * each person's first name.
     */
    public record MessageManyRequest(
            @NotEmpty @Size(max = 300) List<@NotNull Long> employeeIds,
            @NotBlank @Size(max = 500) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG)
            String message) {
    }

    /** @param sent people it went to; notReached people it couldn't reach, each with why */
    public record MessageManyResult(List<String> sent, List<NotReached> notReached) {
    }

    public record NotReached(String name, String reason) {
    }

    /**
     * @param sentBy    the ways it goes out: "Email", "WhatsApp", "Text"
     * @param notSentBy the ways it can't, each with why ("Text: no phone number on file")
     */
    public record DirectMessageResult(List<String> sentBy, List<String> notSentBy) {
    }

    /** @param recipients people the message was queued for; reachable = with a phone and agreement */
    public record AnnounceResult(int recipients, int reachable) {
    }

    /** Errors are shown on the Messages page without the provider's name (older ones were saved with it). */
    static String withoutProvider(String error) {
        if (error == null) {
            return null;
        }
        return error.replace("WhatsApp (Twilio)", "WhatsApp")
                .replace("in the Twilio Console", "on the account")
                .replace("Twilio trial accounts", "Trial texting accounts")
                .replaceAll("(?i)twilio", "messaging service");
    }
}
