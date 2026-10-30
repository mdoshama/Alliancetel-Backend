package com.example.schedulingsoftware.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Reads "Authorization: Bearer ..." and signs the request in as that employee.
 * The employee is re-read each time, so deactivating someone or changing their role takes effect immediately.
 * Created by {@link SecurityConfig}; deliberately not a bean so Boot does not register it twice. */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final EmployeeRepository employeeRepository;
    private final RevokedTokenRepository revokedTokens;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.verify(header.substring(7).trim())
                    // Signed out on this device.
                    .filter(token -> token.id() == null || !revokedTokens.existsById(token.id()))
                    .ifPresent(token -> employeeRepository.findById(token.employeeId())
                            // Issued before "sign out everywhere", a password change or reset: no longer counts.
                            .filter(e -> e.getTokenVersion() == token.version())
                            .filter(Employee::isActive)
                            .filter(e -> e.getPasswordHash() != null)
                            .ifPresent(e -> signIn(e, token)));
        }
        chain.doFilter(request, response);
    }

    private void signIn(Employee e, JwtService.VerifiedToken token) {
        CurrentUser user = new CurrentUser(
                e.getId(),
                e.getEmail(),
                e.getFullName(),
                e.getRole(),
                e.getArea() == null ? null : e.getArea().getId(),
                e.isMustChangePassword());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + e.getRole().name()));
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        // Kept so "sign out" can end exactly this token.
        auth.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
