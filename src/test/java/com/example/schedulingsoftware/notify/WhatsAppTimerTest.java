package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The timer's run must save what happened to each message; otherwise every run sends the same messages again.
 * Not @Transactional on purpose: it checks what was really committed.
 */
@SpringBootTest
class WhatsAppTimerTest {

    @Autowired
    WhatsAppDispatcher dispatcher;
    @Autowired
    NotificationRepository repository;

    @Test
    void aTimerRunSavesTheOutcome() {
        Notification n = new Notification();
        n.setKind(NotificationKind.ANNOUNCEMENT);
        n.setTemplate("team_announcement");
        n.setParamList(List.of("Admin", "Area", "Hello"));
        n.setPreview("Hello");
        n.setPhone("+16195550101");
        Long id = repository.save(n).getId();
        try {
            dispatcher.scheduled(); // WhatsApp isn't connected in tests: the message is recorded as not sent
            assertEquals(NotificationStatus.NOT_SENT, repository.findById(id).orElseThrow().getStatus());
        } finally {
            repository.deleteById(id);
        }
    }
}
