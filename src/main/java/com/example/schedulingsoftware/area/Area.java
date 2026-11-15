package com.example.schedulingsoftware.area;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "areas")
@Getter
@Setter
@NoArgsConstructor
public class Area {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Employees here may ask for days off up to this date (a manager or admin opened requests further ahead than
     * usual). Null: the usual window (the week taking requests, at most two weeks ahead).
     */
    @Column(name = "requests_open_until")
    private java.time.LocalDate requestsOpenUntil;

    /** Who opened requests further ahead, and when. */
    @Column(name = "requests_opened_by", length = 120)
    private String requestsOpenedBy;

    @Column(name = "requests_opened_at")
    private Instant requestsOpenedAt;

    /**
     * Day-off requests opened or closed for this area only (by its manager or an admin). Null: the area follows the
     * company-wide setting.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "request_portal_mode", length = 10)
    private com.example.schedulingsoftware.dayoff.PortalMode requestPortalMode;

    /** Who last opened or closed this area's requests, and when. */
    @Column(name = "request_portal_changed_by", length = 120)
    private String requestPortalChangedBy;

    @Column(name = "request_portal_changed_at")
    private Instant requestPortalChangedAt;

    public Area(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
