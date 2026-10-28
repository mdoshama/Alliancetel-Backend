package com.example.schedulingsoftware.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.schedulingsoftware.auth.AuthDtos.ChangePasswordRequest;
import com.example.schedulingsoftware.auth.AuthDtos.LoginRequest;
import com.example.schedulingsoftware.auth.AuthDtos.LoginResponse;
import com.example.schedulingsoftware.auth.AuthDtos.Me;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // getRemoteAddr() is the real client address: server.forward-headers-strategy reads X-Forwarded-For.
        return authService.login(request.email(), request.password(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public Me me() {
        return authService.me();
    }

    /** Returns a fresh token, since the old one belonged to a session with a temporary password. */
    @PostMapping("/change-password")
    public LoginResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request.currentPassword(), request.newPassword());
    }
}
