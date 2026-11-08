package com.example.schedulingsoftware.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Back office staff only use the Time Clock: their own account (/api/auth), the time clock, and reading areas and
 * stores. Everything else is refused, whatever other rules say.
 */
public class BackOfficeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean backOffice = CurrentUser.find().map(CurrentUser::isBackOffice).orElse(false);
        if (backOffice && !allowed(request)) {
            SecurityConfig.writeProblem(response, HttpStatus.FORBIDDEN, "Back office accounts only use the Time Clock.");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (path.startsWith("/api/auth/") || path.startsWith("/api/time-clock")) {
            return true;
        }
        boolean read = "GET".equalsIgnoreCase(request.getMethod());
        return read && (path.startsWith("/api/areas") || path.startsWith("/api/stores"));
    }
}
