package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.store.Store;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One store's end of day: the sales reps logged, and the money counted at close. Replaces the daily sheet tab. */
@Entity
@Table(name = "eod_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_eod_store_date", columnNames = {"store_id", "report_date"}))
@Getter
@Setter
@NoArgsConstructor
public class EodReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "report_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EodStatus status = EodStatus.OPEN;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<EodLine> lines = new ArrayList<>();

    /**
     * The day's accessories split by hand at close (empty = each accessory is credited to whoever sold it). Cleared
     * automatically when the day's accessory total changes, so it never disagrees with the sales.
     */
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<EodAccessoryShare> accessorySplit = new ArrayList<>();

    // Closing figures, entered by whoever closes. Null until entered.

    /** The day's total from the Qpay report (includes bill payments, not just today's sales). */
    @Column(precision = 12, scale = 2)
    private BigDecimal qpayTotal;

    @Column(precision = 12, scale = 2)
    private BigDecimal cash;

    @Column(precision = 12, scale = 2)
    private BigDecimal credit;

    /** Affirm / Acima. */
    @Column(precision = 12, scale = 2)
    private BigDecimal finance;

    @Column(precision = 12, scale = 2)
    private BigDecimal layawayRedeem;

    @Column(precision = 12, scale = 2)
    private BigDecimal newLayaway;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_id")
    private Employee submittedBy;

    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private Employee approvedBy;

    private Instant approvedAt;

    /** Who last sent it back to be corrected, and when (cleared when it's submitted again). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reopened_by_id")
    private Employee reopenedBy;

    private Instant reopenedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public EodReport(Store store, LocalDate date) {
        this.store = store;
        this.date = date;
    }

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
