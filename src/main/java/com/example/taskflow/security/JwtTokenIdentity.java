package com.example.taskflow.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

public record JwtTokenIdentity(long userId, long version) {

    public static Optional<JwtTokenIdentity> from(Jwt jwt) {
        String subject = jwt.getSubject();
        Object version = jwt.getClaims().get("ver");
        if (subject == null || !subject.matches("[1-9][0-9]*")
                || !(version instanceof Long || version instanceof Integer)) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(subject);
            long tokenVersion = ((Number) version).longValue();
            return tokenVersion < 0 ? Optional.empty()
                    : Optional.of(new JwtTokenIdentity(userId, tokenVersion));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
