package com.example.schedulingsoftware.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Row-level rules on top of the role checks in {@link SecurityConfig}.
 * Admins see every area; managers and employees only their own.
 */
@Component
public class AccessPolicy {

    public CurrentUser current() {
        return CurrentUser.find()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Not signed in."));
    }

    /**
     * Returns the area a query should be limited to. Admins get what they asked for (null = all areas);
     * everyone else is pinned to their own area and may not ask for another.
     */
    public Long restrictToArea(Long requestedAreaId) {
        CurrentUser user = current();
        // Back office works across every area (and can only reach the Time Clock, see BackOfficeFilter).
        if (user.isAdmin() || user.isBackOffice()) {
            return requestedAreaId;
        }
        // Market Managers work one of their areas at a time (the app always says which); their home area by default.
        if (user.isMarketManager()) {
            if (requestedAreaId != null) {
                if (!user.manages(requestedAreaId)) {
                    throw new AccessDeniedException("You can only access the areas you manage.");
                }
                return requestedAreaId;
            }
            Long working = workingArea();
            if (working != null && user.manages(working)) {
                return working;
            }
            return user.areaId() != null ? user.areaId()
                    : user.managedAreaIds().stream().sorted().findFirst().orElse(-1L);
        }
        if (requestedAreaId != null && !requestedAreaId.equals(user.areaId())) {
            throw new AccessDeniedException("You can only access your own area.");
        }
        return user.areaId();
    }

    /** Throws unless the user is an admin or belongs to the given area. */
    public void requireArea(Long areaId) {
        CurrentUser user = current();
        if (user.isMarketManager() && user.manages(areaId)) {
            return;
        }
        if (!user.isAdmin() && (areaId == null || !areaId.equals(user.areaId()))) {
            throw new AccessDeniedException("You can only access your own area.");
        }
    }

    /** Throws unless the user is an admin, or a manager of the given area. */
    public void requireManagerOf(Long areaId) {
        CurrentUser user = current();
        if (user.isAdmin()) {
            return;
        }
        if (!user.manages(areaId)) {
            throw new AccessDeniedException("Only the area's manager or an admin can do this.");
        }
    }

    /** The area a Market Manager is working in, as the app sends it with each request (X-Area-Id), or null. */
    private static Long workingArea() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            String value = attrs.getRequest().getHeader("X-Area-Id");
            if (value != null && value.matches("\\d{1,18}")) {
                return Long.valueOf(value);
            }
        }
        return null;
    }
}
