package com.example.taskflow.services;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.config.JwtProperties;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(
            JwtEncoder jwtEncoder,
            JwtProperties properties, Clock clock) {

        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String generateToken(AppUser user) {

        if (user.getId() == null || user.getId() <= 0 || !user.isEnabled()) {
            throw new BadCredentialsException("An active persisted user is required.");
        }
        Instant now = clock.instant();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .audience(List.of(properties.audience()))
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(
                                now.plusSeconds(properties.expiration())
                        )
                        .subject(user.getId().toString())
                        .id(UUID.randomUUID().toString())
                        .claim("ver", user.getTokenVersion())
                        .claim(
                                "roles",
                                List.of(
                                        user.getRole().name()
                                )
                        )
                        .build();

        return jwtEncoder
                .encode(
                        JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)
                )
                .getTokenValue();
    }

    public long getExpirationSeconds() {
        return properties.expiration();
    }
}
