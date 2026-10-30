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

    /** Primary at the home store: never moved away automatically. Ignored without a home store. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean primaryAtStore;

    /** Normal working days in a Mon-Sun week. Most staff work 6 days (one day off). */
    @Column(nullable = false)
    private int workDaysPerWeek = DEFAULT_WORK_DAYS_PER_WEEK;

    @Column(nullable = false)
    private boolean active = true;

    /** Agreed to get schedule updates on WhatsApp (needed before any message is sent). */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean whatsappOptIn;

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

    /** Primaries and managers stay at their home store unless a manager moves them by hand. */
    public boolean isAnchoredToStore() {
        return homeStore != null && (primaryAtStore || role == Role.MANAGER);
    }
}
