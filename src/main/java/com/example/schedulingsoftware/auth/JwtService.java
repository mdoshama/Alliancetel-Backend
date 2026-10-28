package com.example.schedulingsoftware.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

/** Issues and verifies signed (HS256) access tokens. The token only carries the employee id. */
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

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issue(Long employeeId) {
        Instant now = Instant.now();
        Instant expires = now.plus(lifetime);
        String token = Jwts.builder()
                .subject(String.valueOf(employeeId))
                .issuer("alliancetel")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expires);
    }

    /** Returns the employee id if the token is genuine and not expired. */
    public Optional<Long> verify(String token) {
        try {
            String subject = Jwts.parser().verifyWith(key).requireIssuer("alliancetel").build()
                    .parseSignedClaims(token).getPayload().getSubject();
            return Optional.of(Long.valueOf(subject));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
