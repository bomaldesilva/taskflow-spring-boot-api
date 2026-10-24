package com.example.taskflow.integration;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "testuser", roles = "USER")
class TaskPaginationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private Task firstTask;
    private Task secondTask;
    private Task thirdTask;
    private Task fourthTask;
    private Task lastTask;

    @BeforeEach
    void seedTasks() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        owner = new AppUser();
        owner.setUsername("testuser");
        owner.setPassword("not-a-real-password-hash");
        owner.setRole(Role.USER);
        owner = userRepository.saveAndFlush(owner);
        firstTask = saveTask("Alpha Spring", TaskStatus.TODO, 5);
        secondTask = saveTask("beta SPRING", TaskStatus.TODO, 5);
        thirdTask = saveTask("Gamma spring", TaskStatus.COMPLETED, 5);
        fourthTask = saveTask("Alpha Docker", TaskStatus.TODO, 2);
        lastTask = saveTask("No priority", TaskStatus.IN_PROGRESS, null);
    }

    @Test
    void shouldUseDefaultPageSizeAndNewestFirstSort() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].id").value(lastTask.getId().intValue()))
                .andExpect(jsonPath("$.items[0].title").value("No priority"))
                .andExpect(jsonPath("$.items[4].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void shouldLimitDefaultPageToTwentyTasks() throws Exception {
        Task newest = null;
        for (int index = 0; index < 16; index++) {
            newest = saveTask("Additional task " + index, TaskStatus.TODO, 1);
        }

        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].id").value(newest.getId().intValue()))
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void shouldReturnFirstAndPartialLastPages() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[1].id").value(secondTask.getId().intValue()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("page", "2")
                        .param("size", "2")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(lastTask.getId().intValue()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void shouldReturnEmptyOutOfRangePageWithAccurateTotals() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("page", "10")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(10))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void shouldReturnEmptyEnvelopeWhenThereAreNoTasks() throws Exception {
        taskRepository.deleteAll();

        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void shouldAcceptMaximumPageSize() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void shouldCombineFiltersAndTrimCaseInsensitiveTitle() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("priority", "5")
                        .param("title", "  sPrInG  ")
                        .param("sort", "id,asc")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[0].status").value("TODO"))
                .andExpect(jsonPath("$.items[0].priority").value(5))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("priority", "5")
                        .param("title", "spring")
                        .param("sort", "id,asc")
                        .param("size", "1")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(secondTask.getId().intValue()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldFilterExactPriorityAndExcludeOtherAndNullPriorities() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("priority", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fourthTask.getId().intValue()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReturnZeroTotalsWhenFiltersHaveNoMatches() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "COMPLETED")
                        .param("priority", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void shouldBreakEqualPriorityTiesByIdAcrossPages() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("sort", "priority,desc")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("sort", "priority,desc")
                        .param("size", "1")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(secondTask.getId().intValue()));
    }

    @Test
    void shouldApplyRepeatedSortParametersInOrder() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("sort", "priority,desc", "title,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(secondTask.getId().intValue()))
                .andExpect(jsonPath("$.items[1].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[2].id").value(fourthTask.getId().intValue()));
    }

    @Test
    void shouldRespectExplicitIdTieBreakerDirection() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "TODO")
                        .param("sort", "priority,desc", "id,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(secondTask.getId().intValue()))
                .andExpect(jsonPath("$.items[1].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[2].id").value(fourthTask.getId().intValue()));
    }

    @Test
    void shouldDefaultSortDirectionToAscendingWhenOnlyFieldIsProvided() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("sort", "id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[4].id").value(lastTask.getId().intValue()));
    }

    @Test
    void shouldAllowSortingByStatusWithStableIdOrderingWithinStatus() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("sort", "status,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[1].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[2].id").value(firstTask.getId().intValue()))
                .andExpect(jsonPath("$.items[3].id").value(secondTask.getId().intValue()))
                .andExpect(jsonPath("$.items[4].id").value(fourthTask.getId().intValue()));
    }

    @ParameterizedTest
    @MethodSource("literalTitleSearches")
    void shouldTreatSqlWildcardsAndEscapeCharacterLiterally(
            String titleFilter, String matchingTitle, String nonmatchingTitle) throws Exception {
        Task matchingTask = saveTask(matchingTitle, TaskStatus.TODO, 3);
        saveTask(nonmatchingTitle, TaskStatus.TODO, 3);

        mockMvc.perform(get("/api/v1/tasks").param("title", titleFilter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(matchingTask.getId().intValue()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    static Stream<Arguments> literalTitleSearches() {
        return Stream.of(
                Arguments.of("100%", "Reach 100% coverage", "Reach 1000 coverage"),
                Arguments.of("task_one", "task_one is due", "taskXone is due"),
                Arguments.of("urgent!", "urgent! task", "urgent task"),
                Arguments.of("folder\\task", "Review folder\\task", "Review folderXtask")
        );
    }

    @Test
    void shouldReturnEquivalentOwnerScopedLegacyAndPagedFilterResults() throws Exception {
        AppUser otherOwner = saveOtherOwner();
        saveTask(otherOwner, "Foreign Spring", TaskStatus.TODO, 5);
        saveTask(otherOwner, "Finished foreign task", TaskStatus.COMPLETED, 2);

        Set<Long> expectedTodo = Set.of(firstTask.getId(), secondTask.getId(), fourthTask.getId());
        assertEquals(expectedTodo, responseIds(get("/api/v1/tasks/status/TODO"), "$[*].id"));
        assertEquals(expectedTodo, responseIds(get("/api/v1/tasks")
                .param("status", "TODO").param("size", "100"), "$.items[*].id"));

        Set<Long> expectedSpring = Set.of(firstTask.getId(), secondTask.getId(), thirdTask.getId());
        assertEquals(expectedSpring, legacySearchIds("sPrInG"));
        assertEquals(expectedSpring, pagedSearchIds("sPrInG"));
    }

    @Test
    void shouldShareLiteralEscapingAcrossLegacyAndPagedSearches() throws Exception {
        String literal = "100%_!\\path";
        Task matching = saveTask("Review " + literal, TaskStatus.TODO, 3);
        saveTask("Review 1000X!\\path", TaskStatus.TODO, 3);
        saveTask(saveOtherOwner(), "Review " + literal, TaskStatus.TODO, 3);

        Set<Long> expected = Set.of(matching.getId());
        assertEquals(expected, legacySearchIds(literal));
        assertEquals(expected, pagedSearchIds(literal));
    }

    @Test
    void shouldPreserveLegacyDatabaseUppercaseMatchingForGreekSigma() throws Exception {
        Task sigma = saveTask("σ", TaskStatus.TODO, 3);
        saveTask("ς", TaskStatus.TODO, 3);
        saveTask(saveOtherOwner(), "σ", TaskStatus.TODO, 3);

        Set<Long> expectedLegacy = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT id FROM tasks
                WHERE owner_id = ? AND upper(title) LIKE upper(?) ESCAPE '\\'
                """, Long.class, owner.getId(), "%σ%"));

        assertEquals(expectedLegacy, legacySearchIds("σ"));
        assertEquals(Set.of(sigma.getId()), pagedSearchIds("σ"));
    }

    @Test
    void shouldPreserveRawLegacyTitlesWhilePagedTitlesKeepNormalizationAndValidation() throws Exception {
        Task padded = saveTask("Exact  spring  match", TaskStatus.TODO, 3);
        Task whitespace = saveTask("Three   spaces", TaskStatus.TODO, 3);
        Task noSpaces = saveTask("NoSpaces", TaskStatus.TODO, 3);
        AppUser otherOwner = saveOtherOwner();
        saveTask(otherOwner, padded.getTitle(), TaskStatus.TODO, 3);
        saveTask(otherOwner, whitespace.getTitle(), TaskStatus.TODO, 3);
        saveTask(otherOwner, noSpaces.getTitle(), TaskStatus.TODO, 3);

        assertEquals(Set.of(padded.getId()), legacySearchIds("  spring  "));
        assertEquals(Set.of(firstTask.getId(), secondTask.getId(), thirdTask.getId(), padded.getId()),
                pagedSearchIds("  spring  "));

        Set<Long> allOwned = Set.of(firstTask.getId(), secondTask.getId(), thirdTask.getId(),
                fourthTask.getId(), lastTask.getId(), padded.getId(), whitespace.getId(), noSpaces.getId());
        assertEquals(Set.of(whitespace.getId()), legacySearchIds("   "));
        assertEquals(allOwned, pagedSearchIds("   "));
        assertEquals(allOwned, legacySearchIds(""));
        assertEquals(allOwned, pagedSearchIds(""));

        String overlength = "x".repeat(101);
        assertEquals(Set.of(), legacySearchIds(overlength));
        mockMvc.perform(get("/api/v1/tasks").param("title", overlength))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("invalidQueryParameters")
    void shouldRejectInvalidQueryParameters(String name, String[] values) throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param(name, values))
                .andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidQueryParameters() {
        return Stream.of(
                Arguments.of("page", new String[]{"-1"}),
                Arguments.of("page", new String[]{"abc"}),
                Arguments.of("page", new String[]{"2147483648"}),
                Arguments.of("page", new String[]{"2147483647"}),
                Arguments.of("size", new String[]{"0"}),
                Arguments.of("size", new String[]{"101"}),
                Arguments.of("size", new String[]{"abc"}),
                Arguments.of("size", new String[]{"2147483648"}),
                Arguments.of("priority", new String[]{"0"}),
                Arguments.of("priority", new String[]{"6"}),
                Arguments.of("priority", new String[]{"abc"}),
                Arguments.of("priority", new String[]{"2147483648"}),
                Arguments.of("status", new String[]{"CANCELLED"}),
                Arguments.of("title", new String[]{"x".repeat(101)}),
                Arguments.of("sort", new String[]{""}),
                Arguments.of("sort", new String[]{"description,asc"}),
                Arguments.of("sort", new String[]{"id,sideways"}),
                Arguments.of("sort", new String[]{"title,"}),
                Arguments.of("sort", new String[]{",asc"}),
                Arguments.of("sort", new String[]{"title,asc,extra"}),
                Arguments.of("sort", new String[]{"title,asc", "title,desc"}),
                Arguments.of("sort", new String[]{"id,asc", "id,asc"}),
                Arguments.of("sort", new String[]{"title,asc", "description,desc"})
        );
    }

    private Task saveTask(String title, TaskStatus taskStatus, Integer priority) {
        return saveTask(owner, title, taskStatus, priority);
    }

    private Task saveTask(AppUser taskOwner, String title, TaskStatus taskStatus, Integer priority) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription("Pagination integration test");
        task.setStatus(taskStatus);
        task.setPriority(priority);
        task.setOwner(taskOwner);
        return taskRepository.save(task);
    }

    private AppUser saveOtherOwner() {
        AppUser otherOwner = new AppUser();
        otherOwner.setUsername("other-pagination-user");
        otherOwner.setPassword("not-a-real-password-hash");
        otherOwner.setRole(Role.USER);
        return userRepository.saveAndFlush(otherOwner);
    }

    private Set<Long> legacySearchIds(String title) throws Exception {
        return responseIds(get("/api/v1/tasks/search").param("title", title), "$[*].id");
    }

    private Set<Long> pagedSearchIds(String title) throws Exception {
        return responseIds(get("/api/v1/tasks").param("title", title).param("size", "100"), "$.items[*].id");
    }

    private Set<Long> responseIds(MockHttpServletRequestBuilder request, String path) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, path);
        return ids.stream().map(Number::longValue).collect(Collectors.toSet());
    }
}
