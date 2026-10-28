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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DayOffStatus status = DayOffStatus.PENDING;

    @Column(length = 255)
    private String reviewNote;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
