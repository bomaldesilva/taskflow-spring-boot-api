package com.example.taskflow.integration;

import com.example.taskflow.config.JwtConfig;
import com.example.taskflow.config.JwtProperties;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.services.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "security.jwt.issuer=jwt-security-test",
        "security.jwt.audience=jwt-security-api",
        "security.jwt.expiration=900",
        "security.jwt.clock-skew=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtSecurityIntegrationTest {

    private static final String ISSUER = "jwt-security-test";
    private static final String AUDIENCE = "jwt-security-api";
    private static final String PASSWORD = "jwt-security-test-password";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void publicTestKey(DynamicPropertyRegistry registry) {
        // Public 64-byte fixture permits signing HS384 with the SAME key in one test.
        registry.add("security.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdef".repeat(4).getBytes(StandardCharsets.US_ASCII)));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private SecretKey secretKey;

    @Autowired
    private JwtProperties jwtProperties;

    private Account alice;
    private Account bob;

    @BeforeEach
    void registerUsers() throws Exception {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        alice = register("alice");
        bob = register("bob");
    }

    @Test
    void shouldIssueConfiguredClaimsWithImmutableIdAndUniqueTokenId() throws Exception {
        String loginToken = login(alice.username());
        Jwt registration = jwtDecoder.decode(alice.token());
        Jwt login = jwtDecoder.decode(loginToken);

        for (Jwt token : List.of(registration, login)) {
            assertEquals("HS256", token.getHeaders().get("alg"));
            assertEquals(ISSUER, token.getClaimAsString("iss"));
            assertEquals(List.of(AUDIENCE), token.getAudience());
            assertEquals(Long.toString(alice.id()), token.getSubject());
            assertEquals(List.of("USER"), token.getClaimAsStringList("roles"));
            assertNotNull(token.getIssuedAt());
            assertEquals(token.getIssuedAt(), token.getNotBefore());
            assertNotNull(token.getExpiresAt());
            assertEquals(900, Duration.between(token.getIssuedAt(), token.getExpiresAt()).toSeconds());
            assertDoesNotThrow(() -> UUID.fromString(token.getId()));
            assertEquals(0, ((Number) token.getClaim("ver")).longValue());
        }
        assertNotEquals(registration.getId(), login.getId());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(loginToken)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignedClaims")
    void shouldRejectInvalidClaimsInGenuinelySignedTokens(
            String description, Consumer<JwtClaimsSet.Builder> mutation) throws Exception {
        JwtClaimsSet.Builder claims = validClaims(alice);
        mutation.accept(claims);
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectAnotherSigningAlgorithmEvenWithTheCorrectSecret() throws Exception {
        JwtEncoder hs384 = NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS384).build();
        String token = hs384.encode(JwtEncoderParameters.from(validClaims(alice).build())).getTokenValue();

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSignatureTampering() throws Exception {
        String[] parts = alice.token().split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(String.join(".", parts))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldInvalidateOldTokensAndAcceptNewOnesAfterReplacingSigningKey() {
        // Rebuild the same production encoder/decoder with a replacement key, as on restart.
        SecretKey replacement = new SecretKeySpec(
                "replacement-test-signing-key-0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                "HmacSHA256");
        JwtConfig config = new JwtConfig();
        JwtEncoder rotatedEncoder = config.jwtEncoder(replacement);
        JwtDecoder rotatedDecoder = config.jwtDecoder(
                replacement, jwtProperties, userRepository, Clock.systemUTC());
        String newToken = new TokenService(rotatedEncoder, jwtProperties, Clock.systemUTC())
                .generateToken(userRepository.findById(alice.id()).orElseThrow());

        assertDoesNotThrow(() -> jwtDecoder.decode(alice.token()));
        assertThrows(JwtException.class, () -> rotatedDecoder.decode(alice.token()));
        assertDoesNotThrow(() -> rotatedDecoder.decode(newToken));
        assertThrows(JwtException.class, () -> jwtDecoder.decode(newToken));
    }

    @Test
    void shouldKeepAnExistingTokenBoundToItsUserWhenUsernameChanges() throws Exception {
        long taskId = createTask(alice.token(), "Alice's original task");
        AppUser renamed = userRepository.findById(alice.id()).orElseThrow();
        renamed.setUsername("renamed-alice");
        userRepository.saveAndFlush(renamed);
        Account replacement = register("alice");

        assertNotEquals(alice.id(), replacement.id());
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alice's original task"));
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(replacement.token())))
                .andExpect(status().isNotFound());
        assertEquals(Long.toString(alice.id()), jwtDecoder.decode(alice.token()).getSubject());
    }

    @Test
    void shouldNotRebindADeletedUsersTokenToANewAccountWithTheSameUsername() throws Exception {
        Account original = register("reused-username");
        userRepository.deleteById(original.id());
        Account replacement = register("reused-username");

        assertNotEquals(original.id(), replacement.id());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(original.token())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(replacement.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldRevokeTokensAndPreventLoginWhenTheUserIsDisabled() throws Exception {
        AppUser user = userRepository.findById(alice.id()).orElseThrow();
        long originalVersion = user.getTokenVersion();
        user.setEnabled(false);
        user = userRepository.saveAndFlush(user);

        assertTrue(userRepository.findById(alice.id()).orElseThrow().getTokenVersion() > originalVersion);
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(alice.token())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(alice.username())))
                .andExpect(status().isUnauthorized());

        user.setEnabled(true);
        userRepository.saveAndFlush(user);
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(alice.token())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(login(alice.username()))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectStaleRoleTokensAndIssueCurrentRolesAfterLogin() throws Exception {
        AppUser user = userRepository.findById(alice.id()).orElseThrow();
        long previousVersion = user.getTokenVersion();
        user.setRole(Role.ADMIN);
        userRepository.saveAndFlush(user);

        assertTrue(userRepository.findById(alice.id()).orElseThrow().getTokenVersion() > previousVersion);
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(alice.token())))
                .andExpect(status().isUnauthorized());

        String refreshedLogin = login(alice.username());
        Jwt token = jwtDecoder.decode(refreshedLogin);
        assertEquals(List.of("ADMIN"), token.getClaimAsStringList("roles"));
        assertEquals(userRepository.findById(alice.id()).orElseThrow().getTokenVersion(),
                ((Number) token.getClaim("ver")).longValue());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(refreshedLogin)))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectSignedRoleClaimsThatDisagreeWithTheCurrentDatabaseRole() throws Exception {
        String token = jwtEncoder.encode(JwtEncoderParameters.from(validClaims(alice)
                .claim("roles", List.of("ADMIN")).build())).getTokenValue();

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldLogoutAllSessionsForOnlyTheCurrentUserAndAllowNewLogin() throws Exception {
        String secondAliceToken = login(alice.username());
        long aliceTaskId = createTask(alice.token(), "Alice task before logout");
        createTask(bob.token(), "Bob task stays accessible");
        long oldVersion = userRepository.findById(alice.id()).orElseThrow().getTokenVersion();

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(alice.token())))
                .andExpect(status().isNoContent());

        assertEquals(oldVersion + 1, userRepository.findById(alice.id()).orElseThrow().getTokenVersion());
        for (String revoked : List.of(alice.token(), secondAliceToken)) {
            mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(revoked)))
                    .andExpect(status().isUnauthorized());
            // A request outside TaskService proves revocation is applied by the JWT decoder too.
            mockMvc.perform(get("/actuator/info").header("Authorization", bearer(revoked)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(secondAliceToken)))
                .andExpect(status().isUnauthorized());
        assertEquals(oldVersion + 1, userRepository.findById(alice.id()).orElseThrow().getTokenVersion());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(bob.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        String freshToken = login(alice.username());
        Jwt fresh = jwtDecoder.decode(freshToken);
        assertEquals(oldVersion + 1, ((Number) fresh.getClaim("ver")).longValue());
        mockMvc.perform(get("/api/v1/tasks/{id}", aliceTaskId)
                        .header("Authorization", bearer(freshToken)))
                .andExpect(status().isOk());
    }

    @Test
    void shouldPreventAStaleUserSaveFromUndoingLogoutRevocation() throws Exception {
        AppUser staleUser = userRepository.findById(alice.id()).orElseThrow();
        long oldVersion = staleUser.getTokenVersion();

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(alice.token())))
                .andExpect(status().isNoContent());
        staleUser.setUsername("stale-update-must-not-win");

        assertThrows(OptimisticLockingFailureException.class, () -> userRepository.saveAndFlush(staleUser));
        AppUser stored = userRepository.findById(alice.id()).orElseThrow();
        assertEquals(alice.username(), stored.getUsername());
        assertEquals(oldVersion + 1, stored.getTokenVersion());
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(alice.token())))
                .andExpect(status().isUnauthorized());
    }

    private JwtClaimsSet.Builder validClaims(Account account) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(Long.toString(account.id()))
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plusSeconds(900))
                .id(UUID.randomUUID().toString())
                .claim("ver", userRepository.findById(account.id()).orElseThrow().getTokenVersion())
                .claim("roles", List.of("USER"));
    }

    private Account register(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        return new Account(userRepository.findByUsername(username).orElseThrow().getId(),
                username, JsonPath.read(response, "$.token"));
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    private long createTask(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"priority\":3}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return id.longValue();
    }

    private static String credentials(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Stream<Arguments> invalidSignedClaims() {
        return Stream.of(
                invalid("expired", claims -> claims
                        .issuedAt(Instant.now().minusSeconds(1200))
                        .notBefore(Instant.now().minusSeconds(1200))
                        .expiresAt(Instant.now().minusSeconds(600))),
                invalid("future not-before", claims -> claims.notBefore(Instant.now().plusSeconds(120))),
                invalid("future issued-at", claims -> claims.issuedAt(Instant.now().plusSeconds(120))),
                invalid("wrong issuer", claims -> claims.issuer("another-issuer")),
                invalid("wrong audience", claims -> claims.audience(List.of("another-api"))),
                invalid("missing roles", claims -> claims.claims(values -> values.remove("roles"))),
                invalid("empty roles", claims -> claims.claim("roles", List.of())),
                invalid("string roles instead of array", claims -> claims.claim("roles", "USER")),
                invalid("numeric role", claims -> claims.claim("roles", List.of(1))),
                invalid("mixed role types", claims -> claims.claim("roles", List.of("USER", 1))),
                invalid("blank role", claims -> claims.claim("roles", List.of(" "))),
                invalid("object role", claims -> claims.claim("roles", List.of(java.util.Map.of("name", "USER")))),
                invalid("missing expiration", claims -> claims.claims(values -> values.remove("exp"))),
                invalid("missing issued-at", claims -> claims.claims(values -> values.remove("iat"))),
                invalid("missing not-before", claims -> claims.claims(values -> values.remove("nbf"))),
                invalid("missing audience", claims -> claims.claims(values -> values.remove("aud"))),
                invalid("legacy username subject", claims -> claims.subject("alice")),
                invalid("nonpositive user ID", claims -> claims.subject("0")),
                invalid("missing token version", claims -> claims.claims(values -> values.remove("ver"))),
                invalid("negative token version", claims -> claims.claim("ver", -1L)),
                invalid("missing token ID", claims -> claims.claims(values -> values.remove("jti"))),
                invalid("malformed token ID", claims -> claims.id("not-a-uuid")),
                invalid("excessive lifetime", claims -> claims.expiresAt(Instant.now().plusSeconds(7200)))
        );
    }

    private static Arguments invalid(String description, Consumer<JwtClaimsSet.Builder> mutation) {
        return Arguments.of(description, mutation);
    }

    private record Account(long id, String username, String token) {
    }
}
