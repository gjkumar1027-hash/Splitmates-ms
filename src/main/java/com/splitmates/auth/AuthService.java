package com.splitmates.auth;

import com.splitmates.auth.AuthDtos.*;
import com.splitmates.common.ApiException;
import com.splitmates.common.Hashing;
import com.splitmates.config.AppProperties;
import com.splitmates.domain.OtpCode;
import com.splitmates.domain.User;
import com.splitmates.repo.OtpCodeRepository;
import com.splitmates.repo.UserRepository;
import com.splitmates.user.UserDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {
    private static final String[] AVATAR_COLORS = {"#7C3AED", "#EC4899", "#10B981", "#0EA5E9", "#F59E0B", "#F43F5E", "#8B5CF6", "#14B8A6"};
    private static final String[] AVATAR_EMOJIS = {"😎", "🦊", "🐼", "🐯", "🦁", "🐸", "🐵", "🦄", "🐙", "🐨"};

    private final OtpCodeRepository otpRepo;
    private final UserRepository userRepo;
    private final TokenService tokens;
    private final SmsSender sms;
    private final GoogleVerifier google;
    private final AppProperties props;

    public AuthService(OtpCodeRepository otpRepo, UserRepository userRepo, TokenService tokens, SmsSender sms,
                       GoogleVerifier google, AppProperties props) {
        this.otpRepo = otpRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.sms = sms;
        this.google = google;
        this.props = props;
    }

    /** Normalises to E.164; bare 10-digit numbers are treated as Indian (+91). */
    public static String normalisePhone(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (raw.trim().startsWith("+")) return "+" + digits;
        if (digits.length() == 10) return "+91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        throw ApiException.badRequest("Enter a valid mobile number");
    }

    @Transactional
    public OtpResponse requestOtp(OtpRequest req) {
        String phone = normalisePhone(req.phone());
        long recent = otpRepo.countByPhoneAndCreatedAtAfter(phone, Instant.now().minus(Duration.ofHours(1)));
        if (recent >= props.otp().maxPerHour()) {
            throw ApiException.tooMany("Too many OTP requests. Try again in an hour.");
        }
        String code = Hashing.randomDigits(6);
        OtpCode otp = new OtpCode();
        otp.setPhone(phone);
        otp.setCodeHash(Hashing.sha256(phone + ":" + code));
        otp.setExpiresAt(Instant.now().plus(Duration.ofMinutes(props.otp().ttlMinutes())));
        otpRepo.save(otp);
        sms.send(phone, "Your SplitMates code is " + code + ". It expires in " + props.otp().ttlMinutes() + " minutes.");
        return new OtpResponse(phone, props.otp().ttlMinutes() * 60, props.otp().devMode() ? code : null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyOtp(VerifyRequest req) {
        String phone = normalisePhone(req.phone());
        OtpCode otp = otpRepo.findFirstByPhoneAndConsumedFalseOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> ApiException.badRequest("Request a new OTP"));
        if (otp.getExpiresAt().isBefore(Instant.now()) || otp.getAttempts() >= 5) {
            otp.setConsumed(true);
            throw ApiException.badRequest("OTP expired. Request a new one.");
        }
        if (!otp.getCodeHash().equals(Hashing.sha256(phone + ":" + req.code()))) {
            otp.setAttempts(otp.getAttempts() + 1);
            throw ApiException.badRequest("Incorrect OTP");
        }
        otp.setConsumed(true);

        boolean isNew = false;
        User user = userRepo.findByPhoneAndDeletedFalse(phone).orElse(null);
        if (user == null) {
            requireConsent(req.consent());
            user = newUser(req.displayName() == null || req.displayName().isBlank()
                    ? "Friend " + phone.substring(phone.length() - 4) : req.displayName().trim());
            user.setPhone(phone);
            user = userRepo.save(user);
            isNew = true;
        }
        return respond(user, isNew);
    }

    @Transactional
    public AuthResponse google(GoogleRequest req) {
        GoogleVerifier.GoogleUser g = google.verify(req.idToken());
        boolean isNew = false;
        User user = userRepo.findByEmailAndDeletedFalse(g.email()).orElse(null);
        if (user == null) {
            requireConsent(req.consent());
            user = newUser(g.name());
            user.setEmail(g.email());
            user = userRepo.save(user);
            isNew = true;
        }
        return respond(user, isNew);
    }

    // noRollbackFor: a reused token must stay revoked (family revocation) even though we reject the call.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(RefreshRequest req) {
        TokenService.Tokens t = tokens.rotate(req.refreshToken());
        User user = userRepo.findById(t.userId()).orElseThrow();
        return new AuthResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), UserDtos.me(user), false);
    }

    public void logout(RefreshRequest req) {
        tokens.revoke(req.refreshToken());
    }

    private void requireConsent(boolean consent) {
        // DPDP Act 2023: explicit consent at sign-up (NFR-09).
        if (!consent) throw ApiException.badRequest("Please accept the privacy terms to create an account");
    }

    private User newUser(String name) {
        User u = new User();
        u.setDisplayName(name.length() > 60 ? name.substring(0, 60) : name);
        int i = (int) (Math.random() * AVATAR_COLORS.length);
        u.setAvatarColor(AVATAR_COLORS[i]);
        u.setAvatarEmoji(AVATAR_EMOJIS[(int) (Math.random() * AVATAR_EMOJIS.length)]);
        u.setConsentAt(Instant.now());
        return u;
    }

    private AuthResponse respond(User user, boolean isNew) {
        TokenService.Tokens t = tokens.issue(user);
        return new AuthResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), UserDtos.me(user), isNew);
    }
}
