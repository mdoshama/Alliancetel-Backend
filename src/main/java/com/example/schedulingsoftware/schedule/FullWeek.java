package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;

import com.example.schedulingsoftware.employee.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Someone works all 7 days in one week (a manager or admin decided, e.g. they asked for extra shifts): no day off is
 * needed or reserved for them that week, and they're scheduled every day the stores are open.
 */
@Entity
@Table(name = "full_weeks", uniqueConstraints = @UniqueConstraint(name = "uk_full_week_employee_week",
        columnNames = { "employee_id", "week_start" }))
@Getter
@Setter
@NoArgsConstructor
public class FullWeek {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** Monday of the week. */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(length = 120)
    private String setBy;

    private Instant setAt;
}
