package com.example.schedulingsoftware.store;

import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;

import com.example.schedulingsoftware.area.Area;

import jakarta.persistence.Column;
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
}
