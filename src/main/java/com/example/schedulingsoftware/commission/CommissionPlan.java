package com.example.schedulingsoftware.commission;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The commission plan (one row, id 1), with the Sync Sphere compensation sheet as defaults. Admins can change any of
 * it; months already finalized keep the payouts they were finalized with.
 */
@Entity
@Table(name = "commission_plan")
@Getter
@Setter
@NoArgsConstructor
public class CommissionPlan {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    // New activations (NL/AAL): the month's count sets the rate for all of them.
    @Column(nullable = false) private int activationTier1Max = 25;
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal activationTier1Rate = new BigDecimal("1.00");
    @Column(nullable = false) private int activationTier2Max = 70;
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal activationTier2Rate = new BigDecimal("3.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal activationTier3Rate = new BigDecimal("4.00");

    // Upgrades: the month's count sets the rate for all of them.
    @Column(nullable = false) private int upgradeTier1Max = 25;
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal upgradeTier1Rate = new BigDecimal("4.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal upgradeTier2Rate = new BigDecimal("5.00");

    // Flat rates per box.
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal btsRate = new BigDecimal("1.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal homeInternetRate = new BigDecimal("5.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal unlockRate = new BigDecimal("10.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal metroLockedRate = new BigDecimal("10.00");

    /** Not on the sheet: unpaid until an admin decides. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaysAs freeLinePaysAs = PaysAs.NONE;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaysAs reactivationPaysAs = PaysAs.NONE;

    // Accessories, on the month's total after tax (each store's own rate, set on the store) and cost.
    @Column(nullable = false, precision = 6, scale = 3) private BigDecimal costPercent = BigDecimal.ZERO;
    /** Up to and including this: tier 1. */
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal accessoryTier1Max = new BigDecimal("3000.00");
    @Column(nullable = false, precision = 6, scale = 3) private BigDecimal accessoryTier1Percent = new BigDecimal("2");
    /** From this amount up: tier 3. Between tier 1 max and this: tier 2. */
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal accessoryTier3From = new BigDecimal("7000.00");
    @Column(nullable = false, precision = 6, scale = 3) private BigDecimal accessoryTier2Percent = new BigDecimal("10");
    @Column(nullable = false, precision = 6, scale = 3) private BigDecimal accessoryTier3Percent = new BigDecimal("15");
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AccessoryMode accessoryMode = AccessoryMode.WHOLE;

    // Fines and rewards (defaults when recording one).
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal lateFinePerMinute = new BigDecimal("1.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal noCallNoShowFine = new BigDecimal("50.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal noUniformFine = new BigDecimal("25.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal missedMeetingFine = new BigDecimal("100.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal missedSyncMeetingFine = new BigDecimal("50.00");
    @Column(nullable = false, precision = 8, scale = 2) private BigDecimal scorecardReward = new BigDecimal("50.00");

    private Instant updatedAt;

    @Column(length = 120)
    private String updatedBy;
}
