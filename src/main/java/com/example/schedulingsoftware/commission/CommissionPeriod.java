package com.example.schedulingsoftware.commission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A finalized commission month: the payouts as they were when an admin finalized it, so later changes (a reopened EOD,
 * a new rate) don't change what was paid. Reopening the month deletes this snapshot.
 */
@Entity
@Table(name = "commission_periods", uniqueConstraints = @UniqueConstraint(name = "uk_commission_month", columnNames = "period_month"))
@Getter
@Setter
@NoArgsConstructor
public class CommissionPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "2026-11" */
    @Column(name = "period_month", nullable = false, length = 7)
    private String month;

    @Column(nullable = false)
    private Instant finalizedAt;

    @Column(nullable = false, length = 120)
    private String finalizedBy;

    @OneToMany(mappedBy = "period", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CommissionPayout> payouts = new ArrayList<>();
}
