package com.example.taskflow.config;

import com.example.taskflow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Base64;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class JwtPropertiesTest {

    // Public deterministic 32-byte fixture, never a deployment key.
    private static final String PUBLIC_TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(JwtConfig.class)
                    .withBean(UserRepository.class, () -> mock(UserRepository.class));

    @Test
    void shouldApplyDefaultsAndCreateJwtBeansWithAValidKey() {
        contextRunner.withPropertyValues("security.jwt.secret=" + PUBLIC_TEST_SECRET)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JwtProperties properties = context.getBean(JwtProperties.class);

                    assertEquals("taskflow", properties.issuer());
                    assertEquals("taskflow-api", properties.audience());
                    assertEquals(900, properties.expiration());
                    assertEquals(30, properties.clockSkew());
                    assertNotNull(context.getBean(Clock.class));
                    assertNotNull(context.getBean(JwtEncoder.class));
                    assertNotNull(context.getBean(JwtDecoder.class));
                    assertArrayEquals(Base64.getDecoder().decode(PUBLIC_TEST_SECRET),
                            context.getBean(SecretKey.class).getEncoded());
                    assertFalse(properties.toString().contains(PUBLIC_TEST_SECRET),
                            "Property descriptions must redact key material.");
                });
    }

    @Test
    void shouldUseExplicitIssuerAudienceLifetimeAndClockSkew() {
        contextRunner.withPropertyValues(
                        "security.jwt.secret=" + PUBLIC_TEST_SECRET,
                        "security.jwt.issuer=https://identity.taskflow.test",
                        "security.jwt.audience=taskflow-test-api",
                        "security.jwt.expiration=1800",
                        "security.jwt.clock-skew=15")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JwtProperties properties = context.getBean(JwtProperties.class);

                    assertEquals("https://identity.taskflow.test", properties.issuer());
                    assertEquals("taskflow-test-api", properties.audience());
                    assertEquals(1800, properties.expiration());
                    assertEquals(15, properties.clockSkew());
                });
    }

    @ParameterizedTest
    @CsvSource({"60,0", "3600,60"})
    void shouldAcceptLifetimeAndClockSkewBoundaries(long expiration, long clockSkew) {
        contextRunner.withPropertyValues(
                        "security.jwt.secret=" + PUBLIC_TEST_SECRET,
                        "security.jwt.expiration=" + expiration,
                        "security.jwt.clock-skew=" + clockSkew)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertEquals(expiration, properties.expiration());
                    assertEquals(clockSkew, properties.clockSkew());
                });
    }

    @Test
    void shouldRejectMissingSigningKey() {
        contextRunner.run(context -> assertNotNull(context.getStartupFailure(),
                "JWT configuration without a signing key must fail at startup."));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurationProperties")
    void shouldRejectInvalidConfigurationWithoutDisclosingKeyMaterial(String invalidProperty) {
        contextRunner.withPropertyValues(
                        "security.jwt.secret=" + PUBLIC_TEST_SECRET,
                        invalidProperty)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "Invalid JWT configuration must fail at startup.");

                    String suppliedKey = invalidProperty.startsWith("security.jwt.secret=")
                            ? invalidProperty.substring("security.jwt.secret=".length())
                            : PUBLIC_TEST_SECRET;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        String message = cause.getMessage() == null ? "" : cause.getMessage();
                        assertFalse(message.contains(PUBLIC_TEST_SECRET),
                                "Configuration errors must not disclose the signing key.");
                        if (!suppliedKey.isBlank()) {
                            assertFalse(message.contains(suppliedKey),
                                    "Configuration errors must not disclose invalid key material.");
                        }
                    }
                });
    }

    static Stream<String> invalidConfigurationProperties() {
        return Stream.of(
                "security.jwt.secret=",
                "security.jwt.secret=not-valid-base64!",
                "security.jwt.secret=c2hvcnQta2V5",
                "security.jwt.issuer=",
                "security.jwt.audience=",
                "security.jwt.expiration=59",
                "security.jwt.expiration=3601",
                "security.jwt.expiration=not-a-number",
                "security.jwt.clock-skew=-1",
                "security.jwt.clock-skew=61",
                "security.jwt.clock-skew=not-a-number"
        );
    }
}
