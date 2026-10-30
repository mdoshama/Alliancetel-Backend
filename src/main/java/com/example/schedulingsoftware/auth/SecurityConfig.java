package com.example.schedulingsoftware.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.example.schedulingsoftware.employee.EmployeeRepository;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "MANAGER";

    private final JwtService jwtService;
    private final EmployeeRepository employeeRepository;
    private final RevokedTokenRepository revokedTokenRepository;

    @Value("${alliancetel.security.cors-allowed-origins:}")
    private String corsAllowedOrigins;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsSource()))
                // Tokens travel in the Authorization header, never in cookies, so CSRF protection is not needed.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()

                        // Areas and employees are maintained by admins; managers can look at their own team.
                        .requestMatchers(HttpMethod.GET, "/api/areas/**").authenticated()
                        .requestMatchers("/api/areas/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/stores/**").authenticated()
                        .requestMatchers("/api/stores/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/employees/**").hasAnyRole(ADMIN, MANAGER)
                        // Managers rotate their own staff between stores.
                        .requestMatchers(HttpMethod.PATCH, "/api/employees/*/home-store").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/api/employees/**").hasRole(ADMIN)

                        // Everyone can request days off; only managers and admins decide on them.
                        .requestMatchers("/api/day-off-requests/*/approve", "/api/day-off-requests/*/reject",
                                "/api/day-off-requests/approve-many")
                                .hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/api/day-off-requests/**").authenticated()
                        .requestMatchers("/api/notifications/**").hasAnyRole(ADMIN, MANAGER)

                        // Everyone can read a schedule (employees only once published); managers build them.
                        .requestMatchers(HttpMethod.GET, "/api/schedules/**").authenticated()
                        .requestMatchers("/api/schedules/**").hasAnyRole(ADMIN, MANAGER)

                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeProblem(res, HttpStatus.UNAUTHORIZED, "Please sign in."))
                        .accessDeniedHandler((req, res, e) ->
                                writeProblem(res, HttpStatus.FORBIDDEN, "You don't have permission to do that.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, employeeRepository, revokedTokenRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new PasswordChangeRequiredFilter(), JwtAuthenticationFilter.class);
        return http.build();
    }

    /** Only used when the frontend is hosted on a different domain (alliancetel.security.cors-allowed-origins). */
    private CorsConfigurationSource corsSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }

    /**
     * Sign-in is handled by AuthService with JWTs, so Spring's own username/password store is never used.
     * Declaring this stops Spring from creating a default user and logging a generated password.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Sign in through /api/auth/login.");
        };
    }

    /**
     * BCrypt with cost 12 by default (about a quarter second per check on a small server), which makes offline
     * guessing from a leaked database far slower. Older, cheaper hashes are upgraded at the next sign-in.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${alliancetel.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    static void writeProblem(HttpServletResponse res, HttpStatus status, String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase()
                + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
