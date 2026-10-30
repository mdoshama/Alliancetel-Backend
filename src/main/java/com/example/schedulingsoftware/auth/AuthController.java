package com.example.schedulingsoftware.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /** Anyone can turn their own WhatsApp schedule updates on (if they have a phone number on file) or off. */
    @PutMapping("/me/whatsapp")
    public Me whatsapp(@Valid @RequestBody AuthDtos.WhatsAppPreference body) {
        return authService.setWhatsapp(body.optIn());
    }

    /** Anyone can turn their own email schedule updates off or back on. */
    @PutMapping("/me/email")
    public Me email(@Valid @RequestBody AuthDtos.WhatsAppPreference body) {
        return authService.setEmailUpdates(body.optIn());
    }

    /** Returns a fresh token; every other session (other browsers, phones) is signed out. */
    @PostMapping("/change-password")
    public LoginResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        return authService.changePassword(request.currentPassword(), request.newPassword(), http.getRemoteAddr());
    }

    /** Signs out this device: the token used for this call stops working. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        authService.logout();
    }

    /** Signs the person out on every device, e.g. after losing a phone. */
    @PostMapping("/logout-everywhere")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutEverywhere() {
        authService.logoutEverywhere();
    }
}
