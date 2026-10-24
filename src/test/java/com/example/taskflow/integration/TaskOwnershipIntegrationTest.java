package com.example.taskflow.integration;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.dto.request.TaskListRequest;
import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.Role;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.services.TokenService;
import com.example.taskflow.services.TaskService;
import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskOwnershipIntegrationTest {

    private static final String PASSWORD = "ownership-test-password";
    private static final String UPDATE_BODY = """
            {"title":"Unauthorized edit","description":"Must not be stored",
             "status":"COMPLETED","priority":1}
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static long legacyTaskId;

    @DynamicPropertySource
    static void prepareLegacyTask(DynamicPropertyRegistry registry) throws SQLException {
        // Create a real pre-stage-15 row before application startup applies V5.
        // Never disable the new ownership constraint to manufacture a legacy row.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     INSERT INTO tasks (title, description, status, priority, owner_id)
                     VALUES ('Shared legacy task', 'Preserve this unassigned task', 'TODO', 5, NULL)
                     RETURNING id
                     """)) {
            assertTrue(result.next());
            legacyTaskId = result.getLong(1);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private TaskService taskService;

    private Account alice;
    private Account bob;
    private long aliceFirstId;
    private long aliceSecondId;
    private long bobTaskId;

    @BeforeEach
    void createTwoUsersAndTheirTasks() throws Exception {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM tasks WHERE owner_id IS NOT NULL");
        userRepository.deleteAll();

        alice = register("alice");
        bob = register("bob");
        aliceFirstId = saveOwnedTask(alice, "Shared Alpha", TaskStatus.TODO, 5);
        aliceSecondId = saveOwnedTask(alice, "Shared Beta", TaskStatus.TODO, 5);
        saveOwnedTask(alice, "Finished Alice", TaskStatus.COMPLETED, 2);
        bobTaskId = saveOwnedTask(bob, "Shared Bob", TaskStatus.TODO, 5);
        saveOwnedTask(bob, "Finished Bob", TaskStatus.COMPLETED, 2);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAssignCreatorAndAllowEveryMutationOnOwnTask() throws Exception {
        long id = createTask(alice.token(), "Created by Alice");
        assertEquals(alice.id(), ownerId(id));

        mockMvc.perform(get("/api/v1/tasks/{id}", id).header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Created by Alice"))
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(put("/api/v1/tasks/{id}", id)
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Alice edited task","description":"Updated",
                                 "status":"IN_PROGRESS","priority":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(3));

        assertEquals(alice.id(), ownerId(id));
        assertEquals("Alice edited task", taskRepository.findById(id).orElseThrow().getTitle());

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", id)
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(id).orElseThrow().getStatus());
        assertEquals(alice.id(), ownerId(id));

        mockMvc.perform(delete("/api/v1/tasks/{id}", id)
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isNoContent());

        assertFalse(taskRepository.existsById(id));
        assertTrue(userRepository.existsById(alice.id()));
    }

    @Test
    void shouldScopePageItemsAndCountsBeforeApplyingFiltersAndPagination() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(alice.token()))
                        .param("ownerId", Long.toString(bob.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(filteredPage(alice.token(), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aliceFirstId))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(filteredPage(alice.token(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(aliceSecondId))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(filteredPage(bob.token(), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(bobTaskId))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(alice.token()))
                        .param("title", "Finished Bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldScopeLegacyStatusAndSearchRoutesToEachUser() throws Exception {
        for (String path : List.of("/api/v1/tasks/status/TODO", "/api/v1/tasks/search?title=Shared")) {
            mockMvc.perform(get(path).header("Authorization", bearer(alice.token())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.containsInAnyOrder(
                            (int) aliceFirstId, (int) aliceSecondId)));

            mockMvc.perform(get(path).header("Authorization", bearer(bob.token())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(bobTaskId));
        }
    }

    @ParameterizedTest
    @MethodSource("foreignTaskOperations")
    void shouldHideOtherUsersAndLegacyTasksFromUserAndAdmin(
            String method, String suffix, boolean admin) throws Exception {
        String token = admin ? promoteToAdmin(alice) : alice.token();

        for (long inaccessibleId : new long[]{bobTaskId, legacyTaskId, Long.MAX_VALUE}) {
            mockMvc.perform(taskOperation(method, suffix, inaccessibleId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        Task unchanged = taskRepository.findById(bobTaskId).orElseThrow();
        assertEquals("Shared Bob", unchanged.getTitle());
        assertEquals(TaskStatus.TODO, unchanged.getStatus());
        assertEquals(5, unchanged.getPriority());
        assertEquals(bob.id(), ownerId(bobTaskId));
        assertLegacyTaskUnchanged();
    }

    @Test
    void shouldKeepAdminListsAndNewTasksScopedToTheAdmin() throws Exception {
        String token = promoteToAdmin(alice);

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(filteredPage(token, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        for (String path : List.of("/api/v1/tasks/status/TODO", "/api/v1/tasks/search?title=Shared")) {
            mockMvc.perform(get(path).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        assertEquals(alice.id(), ownerId(createTask(token, "Admin's own task")));
        assertLegacyTaskUnchanged();
    }

    @Test
    void shouldIgnoreForgedOwnerFieldsWhenCreating() throws Exception {
        long before = taskRepository.count();
        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Owner forgery","priority":4,
                                 "ownerId":%d,"owner":{"id":%d,"username":"bob"}}
                                """.formatted(bob.id(), bob.id())))
                .andReturn();

        assertEquals(201, result.getResponse().getStatus());
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertEquals(alice.id(), ownerId(id.longValue()));
        assertEquals(before + 1, taskRepository.count());
    }

    @Test
    void shouldIgnoreForgedOwnerFieldsWhenUpdating() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/tasks/{id}", aliceFirstId)
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Edited by Alice","status":"TODO","priority":4,
                                 "ownerId":%d,"owner":{"id":%d,"username":"bob"}}
                                """.formatted(bob.id(), bob.id())))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("Edited by Alice", taskRepository.findById(aliceFirstId).orElseThrow().getTitle());
        assertEquals(alice.id(), ownerId(aliceFirstId));
        assertEquals(bob.id(), ownerId(bobTaskId));
    }

    @Test
    void shouldRejectAValidTokenForAnUnknownUser() throws Exception {
        String token = signedToken(Long.toString(Long.MAX_VALUE), "USER");

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Unknown user task\",\"priority\":3}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectATokenAfterItsUserIsDeleted() throws Exception {
        Account deleted = register("deleted-user");
        userRepository.deleteById(deleted.id());

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(deleted.token())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks/{id}", aliceFirstId)
                        .header("Authorization", bearer(deleted.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnsupportedRoleForAnExistingUser() throws Exception {
        String token = signedToken(Long.toString(alice.id()), "AUDITOR");

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Unsupported role task\",\"priority\":3}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEnforceOwnershipWhenServicesAreCalledWithoutTheController() {
        authenticate(bob);

        assertEquals(bobTaskId, taskService.getTaskById(bobTaskId).getId());
        var page = taskService.getTasks(new TaskListRequest(0, 20, null, null, null, null));
        assertEquals(2, page.getTotalElements());
        assertEquals(List.of(bobTaskId), taskService.getTasksByStatus(TaskStatus.TODO).stream()
                .map(Task::getId).toList());
        assertEquals(List.of(bobTaskId), taskService.searchTasks("Shared").stream()
                .map(Task::getId).toList());

        assertThrows(TaskNotFoundException.class, () -> taskService.getTaskById(aliceFirstId));
        assertThrows(TaskNotFoundException.class, () -> taskService.updateTask(
                aliceFirstId, "Unauthorized edit", "Not stored", TaskStatus.COMPLETED, 1));
        assertThrows(TaskNotFoundException.class,
                () -> taskService.updateStatus(aliceFirstId, TaskStatus.COMPLETED));
        assertThrows(TaskNotFoundException.class, () -> taskService.deleteTask(aliceFirstId));

        Task unchanged = taskRepository.findById(aliceFirstId).orElseThrow();
        assertEquals("Shared Alpha", unchanged.getTitle());
        assertEquals(TaskStatus.TODO, unchanged.getStatus());
        assertEquals(5, unchanged.getPriority());
        assertEquals(alice.id(), ownerId(aliceFirstId));

        authenticate(alice);
        Task created = taskService.createTask("Created directly by Alice", "Service call", 3);
        assertEquals(alice.id(), ownerId(created.getId()));
        assertEquals(4, taskService.getTasks(
                new TaskListRequest(0, 20, null, null, null, null)).getTotalElements());
    }

    @Test
    void shouldRequireAuthenticationWhenServicesAreCalledDirectly() {
        SecurityContextHolder.clearContext();
        long before = taskRepository.count();

        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> taskService.createTask("Anonymous service task", "Not stored", 3));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> taskService.getTasks(new TaskListRequest(0, 20, null, null, null, null)));

        assertEquals(before, taskRepository.count());
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void shouldRejectAnonymousRequestsOnEveryTaskRoute(
            String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method),
                "/api/v1/tasks" + path.replace("{taskId}", Long.toString(aliceFirstId)));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder filteredPage(String token, int page) {
        return get("/api/v1/tasks")
                .header("Authorization", bearer(token))
                .param("status", "TODO")
                .param("priority", "5")
                .param("title", "shared")
                .param("sort", "id,asc")
                .param("size", "1")
                .param("page", Integer.toString(page));
    }

    private MockHttpServletRequestBuilder taskOperation(String method, String suffix, long id) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method),
                "/api/v1/tasks/" + id + suffix);
        if (method.equals("PUT")) {
            builder.contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY);
        } else if (method.equals("PATCH")) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}");
        }
        return builder;
    }

    private Account register(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Account(userRepository.findByUsername(username).orElseThrow().getId(),
                username, JsonPath.read(response, "$.token"));
    }

    private long createTask(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"priority\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return id.longValue();
    }

    private long saveOwnedTask(Account account, String title, TaskStatus status, int priority) {
        Task task = new Task();
        task.setTitle(title);
        task.setStatus(status);
        task.setPriority(priority);
        task.setOwner(userRepository.findById(account.id()).orElseThrow());
        return taskRepository.saveAndFlush(task).getId();
    }

    private String promoteToAdmin(Account account) {
        AppUser user = userRepository.findById(account.id()).orElseThrow();
        user.setRole(Role.ADMIN);
        return tokenService.generateToken(userRepository.saveAndFlush(user));
    }

    private String signedToken(String subject, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("taskflow")
                .audience(List.of("taskflow-api"))
                .subject(subject)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plusSeconds(900))
                .id(UUID.randomUUID().toString())
                .claim("ver", 0L)
                .claim("roles", List.of(role))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private long ownerId(long taskId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private void assertLegacyTaskUnchanged() {
        var legacy = jdbcTemplate.queryForMap(
                "SELECT title, description, status, priority, owner_id FROM tasks WHERE id = ?", legacyTaskId);
        assertEquals("Shared legacy task", legacy.get("title"));
        assertEquals("Preserve this unassigned task", legacy.get("description"));
        assertEquals("TODO", legacy.get("status"));
        assertEquals(5, legacy.get("priority"));
        assertNull(legacy.get("owner_id"));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void authenticate(Account account) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(account.username(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    private static Stream<Arguments> foreignTaskOperations() {
        return Stream.of(false, true).flatMap(admin -> Stream.of(
                Arguments.of("GET", "", admin),
                Arguments.of("PUT", "", admin),
                Arguments.of("PATCH", "/status", admin),
                Arguments.of("DELETE", "", admin)));
    }

    private static Stream<Arguments> protectedOperations() {
        return Stream.of(
                Arguments.of("GET", "", null),
                Arguments.of("GET", "/{taskId}", null),
                Arguments.of("GET", "/status/TODO", null),
                Arguments.of("GET", "/search?title=Shared", null),
                Arguments.of("POST", "", "{\"title\":\"Anonymous task\",\"priority\":3}"),
                Arguments.of("PUT", "/{taskId}", UPDATE_BODY),
                Arguments.of("PATCH", "/{taskId}/status", "{\"status\":\"COMPLETED\"}"),
                Arguments.of("DELETE", "/{taskId}", null));
    }

    private record Account(long id, String username, String token) {
    }
}
