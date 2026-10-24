package com.example.taskflow.security;

import com.example.taskflow.config.JwtProperties;
import com.example.taskflow.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "The access token is invalid or no longer active.", null);
    private final JwtProperties properties;
    private final UserRepository userRepository;
    private final Clock clock;

    public AccessTokenValidator(JwtProperties properties, UserRepository userRepository, Clock clock) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        var identity = JwtTokenIdentity.from(jwt);
        Instant issuedAt = jwt.getIssuedAt();
        Instant notBefore = jwt.getNotBefore();
        Instant expiresAt = jwt.getExpiresAt();
        Object roles = jwt.getClaims().get("roles");
        if (identity.isEmpty() || issuedAt == null || notBefore == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(notBefore)
                || issuedAt.isAfter(clock.instant().plusSeconds(properties.clockSkew()))
                || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofHours(1)) > 0
                || jwt.getAudience() == null || !jwt.getAudience().contains(properties.audience())
                || !(roles instanceof List<?> roleList) || roleList.isEmpty()
                || roleList.stream().anyMatch(role -> !(role instanceof String name) || name.isBlank())) {
            return invalid();
        }
        try {
            if (jwt.getId() == null || !UUID.fromString(jwt.getId()).toString().equals(jwt.getId())) {
                return invalid();
            }
        } catch (IllegalArgumentException exception) {
            return invalid();
        }
        var user = userRepository.findById(identity.get().userId());
        if (user.isEmpty() || !user.get().isEnabled()
                || user.get().getTokenVersion() != identity.get().version()) {
            return invalid();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
