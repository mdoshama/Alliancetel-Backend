package com.example.schedulingsoftware.live;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.schedulingsoftware.auth.CurrentUser;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps one server-sent-events stream per open browser tab and pushes {@link LiveEvent}s to them, so two managers (or
 * a manager and an admin) working on the same week on different devices see each other's changes straight away.
 *
 * <p>Streams live in this server's memory: with more than one backend instance, events would only reach tabs
 * connected to the instance that made the change.
 */
@Slf4j
@Component
public class LiveUpdates {

    /** A tab reconnects on its own after this, which also clears out streams whose tab went away silently. */
    static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    /** One person with many tabs open is fine; this only stops a runaway loop from holding every thread. */
    static final int MAX_STREAMS_PER_USER = 8;

    private record Client(SseEmitter emitter, CurrentUser user, String clientId) {
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();

    public SseEmitter connect(CurrentUser user, String clientId) {
        long open = clients.stream().filter(c -> c.user().id().equals(user.id())).count();
        if (open >= MAX_STREAMS_PER_USER) {
            clients.stream().filter(c -> c.user().id().equals(user.id())).findFirst().ifPresent(this::close);
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Client client = new Client(emitter, user, clientId);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> close(client));
        emitter.onError(e -> clients.remove(client));
        // First message right away: confirms the stream through every proxy on the way.
        send(client, SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        return emitter;
    }

    /**
     * Sends a change to every connected tab that may see it, except the one that made it.
     *
     * @param actorAreaId the area of whoever made the change (null for admins, whose changes reach everyone)
     */
    public void publish(String topic, CurrentUser actor, String originClientId) {
        Long actorAreaId = actor == null || actor.isAdmin() ? null : actor.areaId();
        String byName = actor == null ? null : firstName(actor.fullName());
        Instant now = Instant.now();
        for (Client c : clients) {
            if (originClientId != null && originClientId.equals(c.clientId())) {
                continue;
            }
            if (!reaches(c.user(), topic, actorAreaId)) {
                continue;
            }
            boolean staff = c.user().isAdmin() || c.user().isManager();
            LiveEvent event = new LiveEvent(topic, staff ? byName : null, originClientId, now);
            send(c, SseEmitter.event().name("change").data(event, MediaType.APPLICATION_JSON));
        }
    }

    /**
     * For changes the server makes by itself (the hourly job reserving days off): tells every screen once the
     * transaction has committed, so a screen that reloads sees the change.
     */
    public void publishSystemChange(String topic) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(topic, null, null);
                        }
                    });
        } else {
            publish(topic, null, null);
        }
    }

    /** Whether this person's screens care about a change of this kind made in that area. */
    static boolean reaches(CurrentUser user, String topic, Long actorAreaId) {
        if (user.isBackOffice()) {
            return "timeclock".equals(topic) || "people".equals(topic);
        }
        if (user.isAdmin() || actorAreaId == null) {
            return true;
        }
        return Objects.equals(user.areaId(), actorAreaId);
    }

    /** A comment line every 25 seconds keeps proxies (nginx, Railway's edge) from closing an idle stream. */
    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        for (Client c : clients) {
            send(c, SseEmitter.event().comment("ping"));
        }
    }

    /**
     * On shutdown (a redeploy), ends every stream first. Otherwise the graceful shutdown would wait for these
     * never-ending requests; the browsers reconnect to the new server by themselves.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        for (Client c : clients) {
            close(c);
        }
    }

    int connected() {
        return clients.size();
    }

    private void send(Client c, SseEmitter.SseEventBuilder event) {
        try {
            c.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // The tab went away; it reconnects by itself if it's still open.
            clients.remove(c);
        }
    }

    private void close(Client c) {
        clients.remove(c);
        try {
            c.emitter().complete();
        } catch (RuntimeException e) {
            log.debug("Closing live stream", e);
        }
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        return fullName.trim().split("\\s+")[0];
    }
}
