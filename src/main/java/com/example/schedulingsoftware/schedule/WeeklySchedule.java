package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.schedulingsoftware.area.Area;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One schedule per area, week and kind: the main one, plus the drafts a manager builds by hand (see
 * {@link ScheduleKind}). The week always starts on Monday.
 */
@Entity
@Table(name = "weekly_schedules",
        uniqueConstraints = @UniqueConstraint(name = "uk_schedule_area_week_kind",
                columnNames = {"area_id", "week_start", "kind"}))
@Getter
@Setter
@NoArgsConstructor
public class WeeklySchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScheduleEntry> entries = new ArrayList<>();

    private Instant publishedAt;

    /**
     * When the manager last pressed "Save draft" (a checkpoint on the way to publishing). Null until the first save,
     * and cleared when the draft is rebuilt from scratch.
     */
    private Instant draftSavedAt;

    /** Main schedule or one of the hand-built drafts. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.ColumnDefault("'MAIN'")
    private ScheduleKind kind = ScheduleKind.MAIN;

    /**
     * "Schedule by System": a fingerprint of what the week was built from (days off, staff, stores). While nobody has
     * edited it, the system builds it again when this changes, so it stays current.
     */
    @jakarta.persistence.Column(length = 64)
    private String sourceHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        // The save itself (updatedAt == draftSavedAt) is not a change after the save. Edits set updatedAt first.
        if (draftSavedAt != null && draftSavedAt.equals(updatedAt)) {
            return;
        }
        updatedAt = Instant.now();
    }

    /** Records a "Save draft" checkpoint: everything up to now is saved. */
    public void markDraftSaved() {
        Instant now = Instant.now();
        draftSavedAt = now;
        updatedAt = now;
    }

    /** True when the draft was changed after its last save (or never saved). */
    public boolean isChangedSinceSave() {
        return draftSavedAt == null || (updatedAt != null && updatedAt.isAfter(draftSavedAt));
    }

    public LocalDate getWeekEnd() {
        return weekStart.plusDays(6);
    }
}
