package com.example.schedulingsoftware.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends queued messages (email and WhatsApp) every few seconds. A message that can't go out right now (network
 * trouble, rate limit) is tried again after 1, then 2 minutes; after 3 tries it is marked failed with the reason.
 * Emails carry an idempotency key, so the email service never delivers the same one twice. Run one copy of the
 * backend (as on Railway), or two copies could send the same WhatsApp message.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsAppDispatcher {

    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 50;

    private final NotificationRepository repository;
    private final WhatsAppSettings settings;
    private final WhatsAppClient client;
    private final EmailSettings emailSettings;
    private final EmailClient emailClient;

    /**
     * Runs on a timer. The transaction must start here: a call from inside this class to {@link #dispatch()} would
     * skip Spring's transaction handling, and the outcome of each send would not be saved (so it would be sent again).
     */
    @Scheduled(fixedDelayString = "${alliancetel.whatsapp.dispatch-delay-ms:5000}", initialDelay = 10_000)
    @Transactional
    public void scheduled() {
        dispatch();
    }

    /** @return how many messages were handled */
    @Transactional
    public int dispatch() {
        List<Notification> due = repository.findDue(Instant.now(), PageRequest.of(0, BATCH));
        for (Notification n : due) {
            boolean email = n.getChannel() == NotificationChannel.EMAIL;
            if (email ? !emailSettings.isConnected() : !settings.isConnected()) {
                n.setStatus(NotificationStatus.NOT_SENT);
                n.setError((email ? "Email" : "WhatsApp") + " isn't connected yet, so this message was only recorded.");
                continue;
            }
            n.setAttempts(n.getAttempts() + 1);
            SendResult result = email
                    ? emailClient.send(n.getEmail(), n.getSubject(), n.getBody(), EmailHtml.render(n.getSubject(), n.getBody()),
                            "alliancetel-notification-" + n.getId())
                    : client.send(n.getPhone(), n.getTemplate(), settings.getLanguage(), n.paramList());
            if (result == null) {
                result = SendResult.failed("No answer from the sender.", true);
            }
            if (result.ok()) {
                n.setStatus(NotificationStatus.SENT);
                n.setSentAt(Instant.now());
                n.setProviderMessageId(result.messageId());
                n.setError(null);
            } else if (result.retry() && n.getAttempts() < MAX_ATTEMPTS) {
                n.setError(result.error());
                n.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(n.getAttempts())));
            } else {
                n.setStatus(NotificationStatus.FAILED);
                n.setError(result.error());
                log.warn("{} message {} failed: {}", n.getChannel(), n.getId(), result.error());
            }
        }
        return due.size();
    }
}
