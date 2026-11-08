package com.example.schedulingsoftware.timeclock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.store.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One person's clock-in (required) and clock-out (optional) for a day, recorded by back office staff watching the
 * store cameras. Times are company-local clock times.
 */
@Entity
@Table(name = "time_entries", uniqueConstraints = @UniqueConstraint(name = "uk_time_entry_employee_date",
        columnNames = { "employee_id", "work_date" }))
@Getter
@Setter
@NoArgsConstructor
public class TimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** Where they were seen working. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime clockIn;

    private LocalTime clockOut;

    @Column(length = 120)
    private String clockedInBy;

    @Column(length = 120)
    private String clockedOutBy;

    @Column(length = 255)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
