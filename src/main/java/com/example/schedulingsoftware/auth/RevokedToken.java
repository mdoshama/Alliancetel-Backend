package com.example.schedulingsoftware.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A sign-in token that was signed out before it expired. Kept only until the token would have expired anyway,
 * after which it is useless and the row is deleted.
 */
@Entity
@Table(name = "revoked_tokens", indexes = @Index(name = "idx_revoked_tokens_expires_at", columnList = "expiresAt"))
@Getter
@NoArgsConstructor
public class RevokedToken {

    /** The token's unique id (its "jti"). */
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Instant expiresAt;

    public RevokedToken(String id, Instant expiresAt) {
        this.id = id;
        this.expiresAt = expiresAt;
    }
}
