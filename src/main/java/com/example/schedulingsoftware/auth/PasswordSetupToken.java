package com.example.schedulingsoftware.auth;

import java.time.Instant;

import com.example.schedulingsoftware.employee.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A one-time link to choose a password, sent by email (and text) when an account is created or its password is
 * reset. Only a SHA-256 hash of the link's secret is kept, so the database alone can't be used to sign in.
 */
@Entity
@Table(name = "password_setup_tokens")
@Getter
@Setter
@NoArgsConstructor
public class PasswordSetupToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** True for a new account's welcome link, false for a password reset. */
    @Column(nullable = false)
    private boolean newAccount;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
