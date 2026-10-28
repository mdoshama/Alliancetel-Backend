package com.example.schedulingsoftware.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Someone signed in with a temporary password can only reach /api/auth until they choose a new one. */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean blocked = CurrentUser.find().map(CurrentUser::mustChangePassword).orElse(false)
                && !request.getRequestURI().startsWith("/api/auth/");
        if (blocked) {
            SecurityConfig.writeProblem(response, HttpStatus.FORBIDDEN, "Please change your temporary password first.");
            return;
        }
        chain.doFilter(request, response);
    }
}
