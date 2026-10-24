package com.example.taskflow.integration;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.services.TokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
@ActiveProfiles("prod")
class ProductionConfigurationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void productionEnvironment(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        // Public test key: 32 bytes, used only in this isolated test context.
        registry.add("JWT_SECRET", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("JWT_EXPIRATION", () -> "1800");
    }

    @Autowired
    private Environment environment;

    @Autowired
    private Flyway flyway;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void shouldMigrateAndValidateFreshProductionDatabase() {
        assertEquals(postgres.getJdbcUrl(), environment.getProperty("spring.datasource.url"));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", environment.getProperty("spring.jpa.show-sql"));
        assertNotNull(flyway.info().current());
        assertEquals(0, flyway.info().pending().length);

        AppUser owner = new AppUser();
        owner.setUsername("production-smoke-owner");
        owner.setPassword("not-a-real-password-hash");
        owner.setRole(Role.USER);
        owner = userRepository.saveAndFlush(owner);

        Task task = new Task();
        task.setTitle("Production configuration smoke test");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(3);
        task.setOwner(owner);

        Task savedTask = taskRepository.saveAndFlush(task);

        assertNotNull(savedTask.getId());
        assertEquals(task.getTitle(), taskRepository.findById(savedTask.getId()).orElseThrow().getTitle());
    }

    @Test
    void shouldIssueAndDecodeTokenWithProductionExpirationOverride() {
        AppUser user = new AppUser();
        user.setUsername("production-test-user");
        user.setPassword("not-a-real-password-hash");
        user.setRole(Role.USER);
        user = userRepository.saveAndFlush(user);

        Jwt token = jwtDecoder.decode(tokenService.generateToken(user));

        assertEquals(user.getId().toString(), token.getSubject());
        assertEquals("taskflow", token.getClaimAsString("iss"));
        assertEquals(List.of("taskflow-api"), token.getAudience());
        assertEquals("HS256", token.getHeaders().get("alg"));
        assertNotNull(token.getIssuedAt());
        assertEquals(token.getIssuedAt(), token.getNotBefore());
        assertNotNull(token.getExpiresAt());
        assertDoesNotThrow(() -> UUID.fromString(token.getId()));
        assertEquals(0, ((Number) token.getClaim("ver")).longValue());
        assertEquals(1800, Duration.between(token.getIssuedAt(), token.getExpiresAt()).toSeconds());
    }
}
