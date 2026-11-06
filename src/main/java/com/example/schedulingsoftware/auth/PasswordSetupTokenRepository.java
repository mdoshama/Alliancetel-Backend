package com.example.schedulingsoftware.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordSetupTokenRepository extends JpaRepository<PasswordSetupToken, Long> {

    @Query("select t from PasswordSetupToken t join fetch t.employee where t.tokenHash = :hash")
    Optional<PasswordSetupToken> findByTokenHash(@Param("hash") String hash);

    /** Ends every link this person was sent (a newer link, or the password was set). */
    @Modifying
    @Query("delete from PasswordSetupToken t where t.employee.id = :employeeId")
    int deleteForEmployee(@Param("employeeId") Long employeeId);

    @Modifying
    @Query("delete from PasswordSetupToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
