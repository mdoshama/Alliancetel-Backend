package com.example.schedulingsoftware.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues and verifies signed (HS256) access tokens. A token carries the employee id and their session number
 * ({@code Employee.tokenVersion}); raising that number in the database ends every older token at once.
 */
@Slf4j
@Service
public class JwtService {

    static final String DEV_SECRET = "alliancetel-dev-secret-change-me-in-production-0123456789";

    private final SecretKey key;
    private final Duration lifetime;

    public JwtService(@Value("${alliancetel.security.jwt-secret:" + DEV_SECRET + "}") String secret,
            @Value("${alliancetel.security.token-hours:12}") long tokenHours,
            @Value("${alliancetel.security.require-strong-secret:false}") boolean requireStrongSecret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters.");
        }
        if (requireStrongSecret && DEV_SECRET.equals(secret)) {
            throw new IllegalStateException("JWT_SECRET is still the development value. Set a long random secret.");
        }
        if (DEV_SECRET.equals(secret)) {
            log.warn("Using the built-in development JWT secret. Set JWT_SECRET before deploying.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.lifetime = Duration.ofHours(tokenHours);
    }

    private static final String VERSION_CLAIM = "sv";

    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** A genuine, unexpired token: whose it is, its own id, when it expires and which session number it carries. */
    public record VerifiedToken(Long employeeId, String id, Instant expiresAt, int version) {
    }

    public IssuedToken issue(Long employeeId, int version) {
        Instant now = Instant.now();
        Instant expires = now.plus(lifetime);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(employeeId))
                .claim(VERSION_CLAIM, version)
                .issuer("alliancetel")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expires);
    }

    /** Returns who the token belongs to if it is genuine and not expired. */
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer("alliancetel").build()
                    .parseSignedClaims(token).getPayload();
            // Tokens issued before session numbers existed count as session 0, which every account starts at.
            Integer version = claims.get(VERSION_CLAIM, Integer.class);
            return Optional.of(new VerifiedToken(Long.valueOf(claims.getSubject()), claims.getId(),
                    claims.getExpiration().toInstant(), version == null ? 0 : version));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
