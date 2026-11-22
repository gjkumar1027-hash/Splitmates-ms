package com.splitmates.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String corsOrigins, String publicUrl, Jwt jwt, Otp otp, Google google, Uploads uploads) {
    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {
    }

    public record Otp(boolean devMode, int ttlMinutes, int maxPerHour) {
    }

    public record Google(String clientId) {
    }

    public record Uploads(String dir) {
    }
}
