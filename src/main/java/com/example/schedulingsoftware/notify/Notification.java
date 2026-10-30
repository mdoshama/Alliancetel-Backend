package com.example.schedulingsoftware.notify;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.example.schedulingsoftware.employee.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message (email or WhatsApp) to one person. Saved together with the change that caused it (so nothing is lost if the app
 * restarts) and sent shortly after by {@link WhatsAppDispatcher}.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "ix_notification_status_next", columnList = "status, next_attempt_at"),
        @Index(name = "ix_notification_area_created", columnList = "area_id, created_at") })
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    /** Separates template values in {@link #params}; values never contain control characters. */
    private static final String SEP = "\u001F";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "area_id")
    private Long areaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.ColumnDefault("'WHATSAPP'")
    private NotificationChannel channel = NotificationChannel.WHATSAPP;

    /** WhatsApp: the number it goes to. */
    @Column(length = 20)
    private String phone;

    /** Email: the address it goes to. */
    @Column(length = 120)
    private String email;

    /** Email subject line. */
    @Column(length = 200)
    private String subject;

    /** Email text (the HTML version is made from it when sending). */
    @Column(length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationKind kind;

    @Column(nullable = false, length = 60)
    private String template;

    /** The template's {{1}}, {{2}}, … values. */
    @Column(nullable = false, length = 3000)
    private String params;

    /** The message as the person reads it, for the Messages page. */
    @Column(nullable = false, length = 1500)
    private String preview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private NotificationStatus status = NotificationStatus.QUEUED;

    @Column(length = 500)
    private String error;

    @Column(length = 120)
    private String providerMessageId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (nextAttemptAt == null) {
            nextAttemptAt = createdAt;
        }
    }

    public List<String> paramList() {
        return params.isEmpty() ? List.of() : Arrays.asList(params.split(SEP, -1));
    }

    public void setParamList(List<String> values) {
        this.params = String.join(SEP, values);
    }
}
