package com.example.schedulingsoftware.store;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.ColumnDefault;

import com.example.schedulingsoftware.area.Area;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
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

/** A store location inside an area. Employees have a home store and can be rotated between stores. */
@Entity
@Table(name = "stores",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_area_name", columnNames = {"area_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class Store {

    /** Hours used for any day that has not been set: the usual 10 am – 8 pm. */
    public static final LocalTime DEFAULT_OPENS_AT = LocalTime.of(10, 0);
    public static final LocalTime DEFAULT_CLOSES_AT = LocalTime.of(20, 0);

    /**
     * Colors offered for stores, chosen to be told apart easily in the schedule. Red is left out because the
     * schedule uses it for warnings.
     */
    public static final List<String> PALETTE = List.of(
            "#2563EB", "#16A34A", "#D97706", "#9333EA", "#DB2777",
            "#0891B2", "#65A30D", "#4F46E5", "#EA580C", "#0D9488", "#A16207", "#475569");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(nullable = false, length = 100)
    private String name;

    /** Short label shown in the schedule grid, e.g. "OCN". */
    @Column(length = 12)
    private String code;

    @Column(length = 255)
    private String address;

    /** Fewest people that must work here on any day; the schedule flags days below it. 0 = no minimum. */
    @Column(nullable = false)
    @ColumnDefault("1")
    private int minStaffPerDay = 1;

    @Column(nullable = false)
    private boolean active = true;

    /** Hex color like "#2563EB" used for this store in the schedule. Null means one is picked from the palette. */
    @Column(length = 7)
    private String color;

    /** Opening hours per day of the week. A day with no entry uses the default 10:00–20:00. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "store_hours", joinColumns = @JoinColumn(name = "store_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_store_hours_day", columnNames = {"store_id", "day_of_week"}))
    private List<StoreDayHours> hours = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Store(Area area, String name, String code, String address) {
        this.area = area;
        this.name = name;
        this.code = code;
        this.address = address;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** The short code if set, otherwise the name. */
    public String getLabel() {
        return code == null || code.isBlank() ? name : code;
    }

    /** The chosen color, or a palette color picked from the id for stores that never had one. */
    public String getDisplayColor() {
        if (color != null) {
            return color;
        }
        return PALETTE.get((int) Math.floorMod(id == null ? 0 : id - 1, (long) PALETTE.size()));
    }

    /** Hours on the given weekday, falling back to the default 10:00–20:00. */
    public StoreDayHours hoursOn(DayOfWeek day) {
        return hours.stream().filter(h -> h.getDayOfWeek() == day).findFirst()
                .orElseGet(() -> StoreDayHours.open(day, DEFAULT_OPENS_AT, DEFAULT_CLOSES_AT));
    }

    public boolean isOpenOn(java.time.LocalDate date) {
        return !hoursOn(date.getDayOfWeek()).isClosed();
    }

    /** Replaces all opening hours (one entry per weekday). */
    public void replaceHours(List<StoreDayHours> newHours) {
        hours.clear();
        hours.addAll(newHours);
    }
}
