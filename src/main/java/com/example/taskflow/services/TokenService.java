package com.example.taskflow.services;

import com.example.taskflow.model.AppUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final long expiration;

    public TokenService(
            JwtEncoder jwtEncoder,
            @Value("${security.jwt.expiration}")
            long expiration) {

        this.jwtEncoder = jwtEncoder;
        this.expiration = expiration;
    }

    public String generateToken(AppUser user) {

        Instant now = Instant.now();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("taskflow")
                        .issuedAt(now)
                        .expiresAt(
                                now.plusSeconds(expiration)
                        )
                        .subject(user.getUsername())
                        .claim(
                                "roles",
                                List.of(
                                        user.getRole().name()
                                )
                        )
                        .build();

        return jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                claims
                        )
                )
                .getTokenValue();
    }

    public long getExpirationSeconds() {
        return expiration;
    }
}
