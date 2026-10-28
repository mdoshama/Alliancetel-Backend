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
        if (user.isAdmin()) {
            return requestedAreaId;
        }
        if (requestedAreaId != null && !requestedAreaId.equals(user.areaId())) {
            throw new AccessDeniedException("You can only access your own area.");
        }
        return user.areaId();
    }

    /** Throws unless the user is an admin or belongs to the given area. */
    public void requireArea(Long areaId) {
        CurrentUser user = current();
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
        if (!user.isManager() || areaId == null || !areaId.equals(user.areaId())) {
            throw new AccessDeniedException("Only the area's manager or an admin can do this.");
        }
    }
}
