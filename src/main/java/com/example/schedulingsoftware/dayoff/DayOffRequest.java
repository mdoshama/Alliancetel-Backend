package com.example.schedulingsoftware.dayoff;

import java.time.Instant;
import java.time.LocalDate;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One requested day off. Asking for two days in a week creates two requests. */
@Entity
@Table(name = "day_off_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_day_off_employee_date", columnNames = {"employee_id", "off_date"}))
@Getter
@Setter
@NoArgsConstructor
public class DayOffRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "off_date", nullable = false)
    private LocalDate offDate;

    @Column(length = 255)
    private String reason;

    /**
     * An urgent request: allowed even when someone at the same store is already off that day, and not counted
     * against the weekly limit. It still needs a manager's approval.
     */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean emergency;

    /**
     * Reserved by the system when the week opened for requests (everyone gets their usual days off up front). It is
     * approved, and gives way automatically when the person asks for a different day.
     */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean reserved;

    /**
     * Reserved because it is one of the person's regular days off (set by a manager or admin). Like any reserved day
     * it waits for a decision, but it is never moved to another day automatically: managers handle it.
     */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean regular;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DayOffStatus status = DayOffStatus.PENDING;

    /**
     * A day the system picked for someone who didn't ask, so it gives way to a colleague's real request (the system
     * then picks another day for them). That holds while it's still waiting and also once the system gave it when the
     * week was generated, as long as nobody decided on it by hand. A regular day off is never movable: it is that
     * person's standing day. Same as {@link #isMovable(boolean)} for a week that isn't published.
     */
    public boolean isMovable() {
        return isMovable(false);
    }

    /**
     * @param weekPublished whether that week's schedule is published: a published week keeps the days it was
     *                      published with, so only a day still waiting can move there
     */
    public boolean isMovable(boolean weekPublished) {
        if (!reserved || regular) {
            return false;
        }
        if (status == DayOffStatus.PENDING) {
            return true;
        }
        return !weekPublished && isGivenBySystem();
    }

    /** Approved by the system itself (at generate), not by a manager or admin, and never changed by hand since. */
    public boolean isGivenBySystem() {
        return status == DayOffStatus.APPROVED && SYSTEM.equals(reviewedBy) && decisionChangedAt == null;
    }

    @Column(length = 255)
    private String reviewNote;

    private Instant reviewedAt;

    /** Who decided: a manager's or admin's name, or {@link #SYSTEM} when it was given (or not) automatically. */
    @Column(length = 120)
    private String reviewedBy;

    /** {@link #reviewedBy} when the system decided. */
    public static final String SYSTEM = "System";

    /** Set when a manager or admin changed the decision after it was made: what it was before, who, when and why. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DayOffStatus previousStatus;

    @Column(length = 120)
    private String decisionChangedBy;

    @Column(length = 255)
    private String decisionChangeReason;

    private Instant decisionChangedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Longest text the reason and note columns hold (255 in the database). */
    static final int TEXT_LIMIT = 255;

    /**
     * The system's explanations ("Not given: …" listing who's off where) can run long; anything past the column's
     * size is cut with "…" instead of making the whole save (e.g. generating a week) fail.
     */
    static String fit(String text) {
        if (text == null || text.length() <= TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, TEXT_LIMIT - 1).stripTrailing() + "\u2026";
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = fit(reviewNote);
    }

    public void setReason(String reason) {
        this.reason = fit(reason);
    }

    public void setDecisionChangeReason(String decisionChangeReason) {
        this.decisionChangeReason = fit(decisionChangeReason);
    }
}
