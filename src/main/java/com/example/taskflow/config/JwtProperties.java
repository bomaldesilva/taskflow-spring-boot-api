package com.example.taskflow.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("taskflow") @NotBlank String issuer,
        @DefaultValue("taskflow-api") @NotBlank String audience,
        @DefaultValue("900") @Min(60) @Max(3600) long expiration,
        @DefaultValue("30") @Min(0) @Max(60) long clockSkew
) {
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, issuer=" + issuer + ", audience=" + audience
                + ", expiration=" + expiration + ", clockSkew=" + clockSkew + "]";
    }
}
