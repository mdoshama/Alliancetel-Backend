package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;

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
 * One person's part of the day's accessories when the closer splits them by hand at the end of the day (instead of
 * crediting each accessory to whoever sold it). The parts add up to the day's accessory total.
 */
@Entity
@Table(name = "eod_accessory_shares",
        uniqueConstraints = @UniqueConstraint(name = "uk_eod_acc_share", columnNames = {"report_id", "employee_id"}))
@Getter
@Setter
@NoArgsConstructor
public class EodAccessoryShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private EodReport report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    public EodAccessoryShare(EodReport report, Employee employee, BigDecimal amount) {
        this.report = report;
        this.employee = employee;
        this.amount = amount;
    }
}
