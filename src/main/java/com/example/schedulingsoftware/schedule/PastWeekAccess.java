package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.employee.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A manager asking an admin to open a week that has already passed, so its schedule can be changed. Once approved,
 * the manager can make changes to that week until {@link #openUntil}.
 */
@Entity
@Table(name = "past_week_access")
@Getter
@Setter
@NoArgsConstructor
public class PastWeekAccess {

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    /** Monday of the week. */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private Employee requestedBy;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(length = 120)
    private String decidedBy;

    private Instant decidedAt;

    /** Approved: changes are allowed until then. */
    private Instant openUntil;

    @Column(length = 500)
    private String note;
}
