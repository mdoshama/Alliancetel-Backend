package com.example.schedulingsoftware.employee;

import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;

import com.example.schedulingsoftware.area.Area;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    public static final int DEFAULT_WORK_DAYS_PER_WEEK = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String employeeCode;

    @Column(nullable = false, length = 60)
    private String firstName;

    @Column(nullable = false, length = 60)
    private String lastName;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(length = 30)
    private String phone;

    /** Date of birth (optional). Admins and managers get a reminder before the birthday; see BirthdayService. */
    private java.time.LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.EMPLOYEE;

    /** Required for managers and employees; admins may sit outside any area. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id")
    private Area area;

    /** Where this person normally works. Weekly schedules start from it; managers can rotate them elsewhere. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_store_id")
    private Store homeStore;

    /**
     * The stores auto-fill may send this person to when they float or cover (other than their home store). Empty
     * means any store in their area.
     */
    @jakarta.persistence.ManyToMany(fetch = FetchType.LAZY)
    @jakarta.persistence.JoinTable(name = "employee_float_stores",
            joinColumns = @JoinColumn(name = "employee_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id"))
    private java.util.Set<Store> floatStores = new java.util.HashSet<>();

    /** Primary at the home store: never moved away automatically. Ignored without a home store. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean primaryAtStore;

    /** Normal working days in a Mon-Sun week. Most staff work 6 days (one day off). */
    @Column(nullable = false)
    private int workDaysPerWeek = DEFAULT_WORK_DAYS_PER_WEEK;

    /**
     * Days of the week this person is normally off (set by managers and admins). When a week opens for requests,
     * these are reserved for them first; they still wait for approval like any reserved day.
     */
    @jakarta.persistence.ElementCollection(fetch = FetchType.EAGER)
    @jakarta.persistence.CollectionTable(name = "employee_regular_days_off",
            joinColumns = @JoinColumn(name = "employee_id"))
    @Column(name = "day_of_week", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.BatchSize(size = 100)
    private java.util.Set<java.time.DayOfWeek> regularDaysOff = new java.util.HashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Works shifts and is on the weekly schedule. Employees always are; a manager who only manages (doesn't work at
     * a store) is off the schedule: not scheduled, no day-off requests or reserved days, not counted for coverage,
     * not on the time clock.
     */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean onSchedule = true;

    /**
     * In training: shown as "Trainee" and listed after everyone else on the schedule. Otherwise works like an employee
     * (shifts, day-off requests). Only for the EMPLOYEE role.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean trainee;

    /** Agreed to get schedule updates on WhatsApp (needed before any message is sent). */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean whatsappOptIn;

    /**
     * Agreed to get schedule updates by text message (SMS). The person turns it on themselves, or an admin does after
     * they agreed (recorded in {@link #smsConsentBy}); it's turned off when their phone number changes.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean smsOptIn;

    /** When they last agreed to text messages (shown the SMS Terms and consent wording), for the carrier records. */
    private java.time.Instant smsConsentAt;

    /** The admin who recorded their agreement to texts; null when they turned it on themselves. */
    @Column(length = 120)
    private String smsConsentBy;

    /** Gets schedule updates by email (on unless they turn it off). */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean emailOptIn = true;

    /** BCrypt hash. Null means this person has no login yet. */
    @Column(length = 100)
    private String passwordHash;

    /** Set when an admin issues a temporary password; cleared once the employee picks their own. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean mustChangePassword;

    private Instant lastLoginAt;

    /** When the current password (or temporary password) was set. Temporary passwords stop working after a while. */
    private Instant passwordSetAt;

    /**
     * Every sign-in token carries this number. Raising it ends all of this person's sessions at once: on "sign out
     * everywhere", password change or reset, deactivation and role or email changes.
     */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int tokenVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Signs this person out everywhere: tokens issued before this call stop working. */
    public void endAllSessions() {
        tokenVersion++;
    }

    /** Stores a new password hash; temporary ones must be changed at the next sign-in. Ends existing sessions. */
    public void setPassword(String hash, boolean temporary) {
        this.passwordHash = hash;
        this.mustChangePassword = temporary;
        this.passwordSetAt = Instant.now();
        endAllSessions();
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    /** Primary or secondary at the home store, or a floater without one. */
    public StoreRole getStoreRole() {
        if (homeStore == null) {
            return StoreRole.FLOATER;
        }
        return primaryAtStore ? StoreRole.PRIMARY : StoreRole.SECONDARY;
    }

    /** Ids of the stores they may float to; empty = any store in the area. */
    public java.util.Set<Long> floatStoreIds() {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        floatStores.forEach(s -> ids.add(s.getId()));
        return ids;
    }

    /** Primaries and managers stay at their home store unless a manager moves them by hand. */
    /** Active store staff who work shifts (see {@link #onSchedule}). */
    public boolean worksShifts() {
        return active && onSchedule && (role == Role.EMPLOYEE || role == Role.MANAGER);
    }

    public boolean isAnchoredToStore() {
        return homeStore != null && (primaryAtStore || role == Role.MANAGER);
    }
}
