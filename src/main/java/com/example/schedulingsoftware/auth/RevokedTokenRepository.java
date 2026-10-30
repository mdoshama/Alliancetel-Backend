package com.example.schedulingsoftware.auth;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {

    /** Forgets tokens that have expired on their own; they can't be used anyway. */
    @Modifying
    @Query("delete from RevokedToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
