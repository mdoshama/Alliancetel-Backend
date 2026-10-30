package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;

import org.hibernate.annotations.ColumnDefault;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.store.Store;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** What one employee does on one day of a weekly schedule. */
@Entity
@Table(name = "schedule_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_entry_schedule_employee_date",
                columnNames = {"schedule_id", "employee_id", "work_date"}))
@Getter
@Setter
@NoArgsConstructor
public class ScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WeeklySchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DayType type;

    /** Where the employee works that day. Only set on WORK days; null means not yet assigned to a store. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    /** True when the scheduler sent this person to a store other than their home store to cover a minimum. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean autoAssigned;

    /** True once a manager has changed the cell by hand. */
    @Column(nullable = false)
    private boolean manuallyEdited;

    /**
     * How this day read when the week was last published (e.g. "Oceanside 10am–8pm" or "Off"), so a later change can
     * be sent to just the people whose days changed. Null until first published.
     */
    @Column(length = 120)
    private String publishedLabel;

    public ScheduleEntry(WeeklySchedule schedule, Employee employee, LocalDate workDate, DayType type, Store store) {
        this.schedule = schedule;
        this.employee = employee;
        this.workDate = workDate;
        this.type = type;
        this.store = type == DayType.WORK ? store : null;
    }
}
