package com.splitmates.user;

import com.splitmates.domain.Theme;
import com.splitmates.domain.User;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class UserDtos {
    private UserDtos() {
    }

    public record MeDto(Long id, String displayName, String phone, String email, String avatarEmoji, String avatarColor,
                        String upiId, Theme theme, boolean notifyInApp, boolean notifyWeekly, boolean showPhone,
                        Instant createdAt) {
    }

    public record UpdateProfileRequest(
            @Size(min = 1, max = 60) String displayName,
            @Size(max = 16) String avatarEmoji,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Colour must be like #7C3AED") String avatarColor,
            @Pattern(regexp = "^$|^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$", message = "Enter a valid UPI ID, e.g. name@okaxis") String upiId,
            Theme theme,
            Boolean notifyInApp,
            Boolean notifyWeekly,
            Boolean showPhone) {
    }

    public static MeDto me(User u) {
        return new MeDto(u.getId(), u.getDisplayName(), u.getPhone(), u.getEmail(), u.getAvatarEmoji(), u.getAvatarColor(),
                u.getUpiId(), u.getTheme(), u.isNotifyInApp(), u.isNotifyWeekly(), u.isShowPhone(), u.getCreatedAt());
    }
}
