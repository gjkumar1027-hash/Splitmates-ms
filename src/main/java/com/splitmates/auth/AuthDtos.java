package com.splitmates.auth;

import com.splitmates.user.UserDtos.MeDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record OtpRequest(@NotBlank @Pattern(regexp = "^\\+?[0-9 \\-]{10,16}$", message = "Enter a valid mobile number") String phone) {
    }

    public record OtpResponse(String phone, int expiresInSeconds, String devCode) {
    }

    public record VerifyRequest(@NotBlank String phone,
                                @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "OTP is 6 digits") String code,
                                @Size(max = 60) String displayName,
                                boolean consent) {
    }

    public record GoogleRequest(@NotBlank String idToken, boolean consent) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record AuthResponse(String accessToken, String refreshToken, long expiresIn, MeDto user, boolean newUser) {
    }
}
