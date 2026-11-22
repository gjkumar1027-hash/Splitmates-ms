package com.splitmates.auth;

import com.splitmates.common.ApiException;
import com.splitmates.common.Hashing;
import com.splitmates.config.AppProperties;
import com.splitmates.domain.RefreshToken;
import com.splitmates.domain.User;
import com.splitmates.repo.RefreshTokenRepository;
import com.splitmates.repo.UserRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenService {
    public record Tokens(String accessToken, String refreshToken, long expiresIn, Long userId) {
    }

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshRepo;
    private final UserRepository userRepo;
    private final AppProperties props;

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshRepo, UserRepository userRepo, AppProperties props) {
        this.encoder = encoder;
        this.refreshRepo = refreshRepo;
        this.userRepo = userRepo;
        this.props = props;
    }

    @Transactional
    public Tokens issue(User user) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofMinutes(props.jwt().accessTtlMinutes());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("splitmates")
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("name", user.getDisplayName())
                .build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        String refresh = Hashing.randomToken(32);
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(Hashing.sha256(refresh));
        rt.setExpiresAt(now.plus(Duration.ofDays(props.jwt().refreshTtlDays())));
        refreshRepo.save(rt);
        return new Tokens(access, refresh, ttl.toSeconds(), user.getId());
    }

    /** Rotating refresh tokens (NFR-06): each refresh token is single use. */
    @Transactional(noRollbackFor = ApiException.class)
    public Tokens rotate(String refreshToken) {
        RefreshToken rt = refreshRepo.findByTokenHash(Hashing.sha256(refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (rt.isRevoked()) {
            // Reuse of a rotated token: assume theft and revoke the whole family.
            refreshRepo.revokeAllForUser(rt.getUserId());
            throw ApiException.unauthorized("Refresh token reused");
        }
        if (rt.getExpiresAt().isBefore(Instant.now())) throw ApiException.unauthorized("Refresh token expired");
        rt.setRevoked(true);
        User user = userRepo.findById(rt.getUserId()).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.unauthorized("Account not found"));
        return issue(user);
    }

    @Transactional
    public void revoke(String refreshToken) {
        refreshRepo.findByTokenHash(Hashing.sha256(refreshToken)).ifPresent(rt -> rt.setRevoked(true));
    }
}
