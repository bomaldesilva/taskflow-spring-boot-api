package com.example.taskflow.integration;

import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = "security.jwt.expiration=1800")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final String CREDENTIALS = """
            {"username":"testuser","password":"test-password"}
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldRegisterLoginAndUseIssuedBearerToken() throws Exception {
        String registrationToken = registerUser();
        assertTokenLifetime(registrationToken);

        var user = userRepository.findByUsername("testuser").orElseThrow();
        assertNotEquals("test-password", user.getPassword());
        assertTrue(passwordEncoder.matches("test-password", user.getPassword()));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString();

        String loginToken = JsonPath.read(loginResponse, "$.token");
        assertTokenLifetime(loginToken);

        String taskResponse = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + loginToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Authenticated task","priority":4}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number taskId = JsonPath.read(taskResponse, "$.id");
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId.longValue())
                        .header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Authenticated task"));

        assertEquals(1, taskRepository.count());
        assertEquals(4, taskRepository.findById(taskId.longValue()).orElseThrow().getPriority());
    }

    @Test
    void shouldRejectIncorrectCredentials() throws Exception {
        registerUser();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"testuser","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void shouldRejectDuplicateUsername() throws Exception {
        registerUser();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertEquals(1, userRepository.count());
    }

    @ParameterizedTest
    @MethodSource("invalidAuthRequests")
    void shouldRejectMalformedOrInvalidAuthInput(String endpoint, String body) throws Exception {
        mockMvc.perform(post("/api/v1/auth/" + endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertEquals(0, userRepository.count());
    }

    @Test
    void shouldRequireAuthenticationForTaskEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTamperedBearerToken() throws Exception {
        String[] tokenParts = registerUser().split("\\.");
        String signature = tokenParts[2];
        tokenParts[2] = (signature.startsWith("A") ? "B" : "A") + signature.substring(1);

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + String.join(".", tokenParts)))
                .andExpect(status().isUnauthorized());
    }

    private String registerUser() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.token");
    }

    private void assertTokenLifetime(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        assertEquals("HS256", jwt.getHeaders().get("alg"));
        assertEquals("taskflow", jwt.getClaimAsString("iss"));
        assertEquals(userRepository.findByUsername("testuser").orElseThrow().getId().toString(), jwt.getSubject());
        assertEquals(List.of("taskflow-api"), jwt.getAudience());
        assertEquals(List.of("USER"), jwt.getClaimAsStringList("roles"));
        assertNotNull(jwt.getIssuedAt());
        assertEquals(jwt.getIssuedAt(), jwt.getNotBefore());
        assertNotNull(jwt.getExpiresAt());
        assertDoesNotThrow(() -> UUID.fromString(jwt.getId()));
        assertEquals(0, ((Number) jwt.getClaim("ver")).longValue());
        assertEquals(1800, Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds());
    }

    private static Stream<Arguments> invalidAuthRequests() {
        return Stream.of(
                Arguments.of("register", "{\"username\":\"testuser\",\"password\":"),
                Arguments.of("login", "{\"username\":"),
                Arguments.of("register", "{\"username\":\"testuser\",\"password\":\"short\"}"),
                Arguments.of("login", "{\"username\":\"\",\"password\":\"\"}"),
                Arguments.of("register", "{\"username\":{},\"password\":\"test-password\"}")
        );
    }
}
