package com.example.schedulingsoftware.auth;

import org.springframework.mock.web.MockHttpServletRequest;

/** Test helper: what a back office account may reach. */
public final class BackOfficeFilterAccess {

    private BackOfficeFilterAccess() {
    }

    public static boolean allowed(String method, String path) {
        return BackOfficeFilter.allowed(new MockHttpServletRequest(method, path));
    }
}
