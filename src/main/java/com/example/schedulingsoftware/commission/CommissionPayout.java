package com.example.schedulingsoftware.commission;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One person's finalized pay for a month (a copy of the calculator's breakdown at the time). */
@Entity
@Table(name = "commission_payouts")
@Getter
@Setter
@NoArgsConstructor
public class CommissionPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private CommissionPeriod period;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    private int activations;
    @Column(precision = 8, scale = 2) private BigDecimal activationRate;
    @Column(precision = 10, scale = 2) private BigDecimal activationPay;
    private int upgrades;
    @Column(precision = 8, scale = 2) private BigDecimal upgradeRate;
    @Column(precision = 10, scale = 2) private BigDecimal upgradePay;
    private int bts;
    @Column(precision = 10, scale = 2) private BigDecimal btsPay;
    private int homeInternet;
    @Column(precision = 10, scale = 2) private BigDecimal homeInternetPay;
    private int unlockCount;
    @Column(precision = 10, scale = 2) private BigDecimal unlockPay;
    private int metroLocked;
    @Column(precision = 10, scale = 2) private BigDecimal metroLockedPay;
    @Column(precision = 12, scale = 2) private BigDecimal accessoriesGross;
    @Column(precision = 12, scale = 2) private BigDecimal accessoriesNet;
    @Column(precision = 8, scale = 3) private BigDecimal accessoryPercent;
    @Column(precision = 10, scale = 2) private BigDecimal accessoryPay;
    @Column(precision = 10, scale = 2) private BigDecimal boxPay;
    @Column(precision = 10, scale = 2) private BigDecimal fines;
    @Column(precision = 10, scale = 2) private BigDecimal rewards;
    @Column(precision = 12, scale = 2) private BigDecimal total;

    public CommissionPayout(CommissionPeriod period, Employee employee, CommissionCalculator.Breakdown b) {
        this.period = period;
        this.employee = employee;
        activations = b.activations();
        activationRate = b.activationRate();
        activationPay = b.activationPay();
        upgrades = b.upgrades();
        upgradeRate = b.upgradeRate();
        upgradePay = b.upgradePay();
        bts = b.bts();
        btsPay = b.btsPay();
        homeInternet = b.homeInternet();
        homeInternetPay = b.homeInternetPay();
        unlockCount = b.unlock();
        unlockPay = b.unlockPay();
        metroLocked = b.metroLocked();
        metroLockedPay = b.metroLockedPay();
        accessoriesGross = b.accessoriesGross();
        accessoriesNet = b.accessoriesNet();
        accessoryPercent = b.accessoryPercent();
        accessoryPay = b.accessoryPay();
        boxPay = b.boxPay();
        fines = b.fines();
        rewards = b.rewards();
        total = b.total();
    }

    public CommissionCalculator.Breakdown toBreakdown() {
        return new CommissionCalculator.Breakdown(activations, activationRate, activationPay, upgrades, upgradeRate,
                upgradePay, bts, btsPay, homeInternet, homeInternetPay, unlockCount, unlockPay, metroLocked,
                metroLockedPay, accessoriesGross, accessoriesNet, accessoryPercent, accessoryPay, boxPay, fines,
                rewards, total);
    }
}
