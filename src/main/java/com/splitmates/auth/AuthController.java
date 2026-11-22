package com.splitmates.auth;

import com.splitmates.auth.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/otp")
    public OtpResponse otp(@Valid @RequestBody OtpRequest req) {
        return auth.requestOtp(req);
    }

    @PostMapping("/verify")
    public AuthResponse verify(@Valid @RequestBody VerifyRequest req) {
        return auth.verifyOtp(req);
    }

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleRequest req) {
        return auth.google(req);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req);
        return ResponseEntity.noContent().build();
    }
}
