package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One sale on an end-of-day report. For a phone or service sale, what the customer paid ({@code soldFor}) splits into
 * QPAY + subtotal (typed by the rep) + accessories; for an accessory sale, QPAY and subtotal are zero and it's all
 * accessories.
 */
@Entity
@Table(name = "eod_lines")
@Getter
@Setter
@NoArgsConstructor
public class EodLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private EodReport report;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EodLineType type;

    /** Only for sales. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EodCategory category;

    /** e.g. "SAM A16", "BYOD", "All accessories". */
    @Column(nullable = false, length = 120)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal qpay = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal retail = BigDecimal.ZERO;

    /** Old sales only: tax that used to be worked out on the retail price. New sales keep it at zero. */
    @Column(nullable = false, precision = 12, scale = 2)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal tax = BigDecimal.ZERO;

    /** The store's tax rate (percent) when the sale was saved; commission uses it to take tax off accessories. */
    @Column(nullable = false, precision = 6, scale = 3)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal taxRate = BigDecimal.ZERO;

    /** The subtotal as the rep wrote it. Null on sales saved before it was typed by hand (then retail + tax). */
    @Column(precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal soldFor = BigDecimal.ZERO;

    /** Customer number as written (kept as text so leading zeros stay), or "Walk in". */
    @Column(length = 30)
    private String customerRef;

    /** Unlock / Metro-locked sales. */
    @Column(length = 4)
    private String imeiLast4;

    @Column(length = 20)
    private String phone;

    /** Who made the sale (commission goes to them). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rep_id", nullable = false)
    private Employee rep;

    /**
     * Colleagues who share this sale (they worked the customer together). The box and the accessories are credited
     * evenly between the rep and them.
     */
    @ManyToMany
    @JoinTable(name = "eod_line_shares", joinColumns = @JoinColumn(name = "line_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id"))
    private List<Employee> sharedWith = new ArrayList<>();

    /** The device picked from the device list, if any (kept as a plain id: removing a device keeps old sales). */
    @Column(name = "device_id")
    private Long deviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private Employee createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** What the rep wrote; older sales fall back to retail + tax. */
    public BigDecimal getSubtotal() {
        return subtotal != null ? subtotal : retail.add(tax);
    }

    /** The accessory part: sold for − QPAY − subtotal. */
    public BigDecimal getAccessories() {
        return soldFor.subtract(qpay).subtract(getSubtotal());
    }
}
