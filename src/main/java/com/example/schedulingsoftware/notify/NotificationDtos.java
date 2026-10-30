package com.example.schedulingsoftware.notify;

import java.time.Instant;
import java.util.List;

import com.example.schedulingsoftware.common.InputRules;

import jakarta.validation.constraints.NotBlank;
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
                    n.getChannel() == NotificationChannel.EMAIL && n.getBody() != null ? n.getBody() : n.getPreview(),
                    n.getError(), n.getAttempts(), n.getCreatedAt(), n.getSentAt());
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
            boolean emailConnected, String emailFrom, long emailOff) {
    }

    public record AnnounceRequest(
            @NotNull Long areaId,
            @NotBlank @Size(max = 500) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG)
            String message) {
    }

    /** @param recipients people the message was queued for; reachable = with a phone and agreement */
    public record AnnounceResult(int recipients, int reachable) {
    }
}
