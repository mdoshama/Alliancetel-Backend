package com.example.schedulingsoftware.auth;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.schedulingsoftware.employee.Role;

/** The signed-in user, loaded fresh from the database on every request. */
public record CurrentUser(
        Long id,
        String email,
        String fullName,
        Role role,
        Long areaId,
        boolean mustChangePassword,
        /** Areas this person manages: their own for a manager, the assigned ones (and their own) for a Market Manager. */
        java.util.Set<Long> managedAreaIds) {

    public CurrentUser {
        managedAreaIds = managedAreaIds == null ? java.util.Set.of() : java.util.Set.copyOf(managedAreaIds);
    }

    /** Managers manage their own area. */
    public CurrentUser(Long id, String email, String fullName, Role role, Long areaId, boolean mustChangePassword) {
        this(id, email, fullName, role, areaId, mustChangePassword,
                (role == Role.MANAGER || role == Role.MARKET_MANAGER) && areaId != null ? java.util.Set.of(areaId)
                        : java.util.Set.of());
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** A manager of either kind: of one area, or a Market Manager of several. */
    public boolean isManager() {
        return role == Role.MANAGER || role == Role.MARKET_MANAGER;
    }

    public boolean isMarketManager() {
        return role == Role.MARKET_MANAGER;
    }

    /** Admins manage every area; managers the areas they were given. */
    public boolean manages(Long area) {
        return isAdmin() || (isManager() && area != null && managedAreaIds.contains(area));
    }

    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }

    public boolean isBackOffice() {
        return role == Role.BACK_OFFICE;
    }

    public static Optional<CurrentUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
