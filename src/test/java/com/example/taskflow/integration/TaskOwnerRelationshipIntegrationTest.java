package com.example.taskflow.integration;

import com.example.taskflow.dto.response.TaskResponse;
import com.example.taskflow.mappers.TaskMapper;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "testuser", roles = "USER")
class TaskOwnerRelationshipIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void shouldPersistMultipleTasksWithTheSameLazyOwner() {
        AppUser owner = saveOwner();
        Task firstTask = saveTask("First owned task", owner);
        Task secondTask = saveTask("Second owned task", owner);

        transactions.executeWithoutResult(transaction -> {
            entityManager.clear();
            Task firstReloaded = entityManager.find(Task.class, firstTask.getId());
            Task secondReloaded = entityManager.find(Task.class, secondTask.getId());

            assertFalse(Hibernate.isInitialized(firstReloaded.getOwner()));
            assertFalse(Hibernate.isInitialized(secondReloaded.getOwner()));
            assertEquals("relationship-owner", firstReloaded.getOwner().getUsername());
            assertTrue(Hibernate.isInitialized(firstReloaded.getOwner()));
            assertEquals(owner.getId(), firstReloaded.getOwner().getId());
            assertEquals(owner.getId(), secondReloaded.getOwner().getId());
            assertEquals("relationship-owner", secondReloaded.getOwner().getUsername());
        });

        assertEquals(2, taskRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void shouldMapDetachedTaskWithoutInitializingItsOwner() {
        AppUser owner = saveOwner();
        Task savedTask = saveTask("Detached task", owner);

        Task detachedTask = transactions.execute(transaction -> {
            entityManager.clear();
            Task task = entityManager.find(Task.class, savedTask.getId());
            assertFalse(Hibernate.isInitialized(task.getOwner()));
            return task;
        });

        assertNotNull(detachedTask);
        assertFalse(Hibernate.isInitialized(detachedTask.getOwner()));
        TaskResponse response = taskMapper.toResponse(detachedTask);

        assertEquals(savedTask.getId(), response.id());
        assertEquals("Detached task", response.title());
        assertEquals("Relationship integration test", response.description());
        assertEquals(TaskStatus.TODO, response.status());
        assertEquals(3, response.priority());
        assertFalse(Hibernate.isInitialized(detachedTask.getOwner()));
    }

    @Test
    void shouldPreserveOwnerWhenDeletingItsTask() {
        AppUser owner = saveOwner();
        Task task = saveTask("Task to delete", owner);

        taskRepository.deleteById(task.getId());

        assertFalse(taskRepository.existsById(task.getId()));
        assertTrue(userRepository.existsById(owner.getId()));
        assertEquals(1, userRepository.count());
    }

    @Test
    void shouldRejectDeletingAnOwnerWhileItsTaskStillReferencesIt() {
        AppUser owner = saveOwner();
        Task task = saveTask("Task prevents owner deletion", owner);

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.deleteById(owner.getId()));

        assertTrue(userRepository.existsById(owner.getId()));
        assertTrue(taskRepository.existsById(task.getId()));
    }

    @Test
    void shouldKeepTaskApiResponsesAsDtosWithOpenSessionInViewDisabled() throws Exception {
        AppUser owner = saveOwner();
        Task task = saveTask("Owned task", owner);

        mockMvc.perform(get("/api/v1/tasks/{id}", task.getId()))
                .andExpect(status().isOk())
                .andExpect(taskDtoAt("$"))
                .andExpect(jsonPath("$.title").value("Owned task"));

        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(taskDtoAt("$.items[0]"));

        mockMvc.perform(get("/api/v1/tasks/status/TODO"))
                .andExpect(status().isOk())
                .andExpect(taskDtoAt("$[0]"));

        mockMvc.perform(get("/api/v1/tasks/search").param("title", "Owned"))
                .andExpect(status().isOk())
                .andExpect(taskDtoAt("$[0]"));

        mockMvc.perform(put("/api/v1/tasks/{id}", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Owned task updated","description":"Updated",
                                 "status":"IN_PROGRESS","priority":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(taskDtoAt("$"))
                .andExpect(jsonPath("$.priority").value(4));

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"COMPLETED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(taskDtoAt("$"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"API-created task","description":"Still a DTO","priority":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(taskDtoAt("$"));

        transactions.executeWithoutResult(transaction -> {
            Task updatedTask = entityManager.find(Task.class, task.getId());
            assertEquals(owner.getId(), updatedTask.getOwner().getId());
        });
    }

    private AppUser saveOwner() {
        AppUser owner = new AppUser();
        owner.setUsername("relationship-owner");
        owner.setPassword("not-a-real-password-hash");
        owner.setRole(Role.USER);
        return userRepository.saveAndFlush(owner);
    }

    private Task saveTask(String title, AppUser owner) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription("Relationship integration test");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(3);
        task.setOwner(owner);
        return taskRepository.saveAndFlush(task);
    }

    private ResultMatcher taskDtoAt(String path) {
        return result -> {
            Map<String, Object> response = JsonPath.read(
                    result.getResponse().getContentAsString(), path);
            assertEquals(Set.of("id", "title", "description", "status", "priority"),
                    response.keySet());
        };
    }
}
