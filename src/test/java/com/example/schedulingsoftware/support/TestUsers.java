package com.example.schedulingsoftware.support;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.employee.Role;

/** Puts a user into the security context so services can be called directly in tests. */
public final class TestUsers {

    private TestUsers() {
    }

    public static void signInAs(Long id, Role role, Long areaId) {
        CurrentUser user = new CurrentUser(id, "user" + id + "@test.example", "Test User", role, areaId, false);
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    public static void signInAsAdmin() {
        signInAs(-1L, Role.ADMIN, null);
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
