package com.example.taskflow.integration;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username="testuser", roles="USER")
class TaskApiIntegrationTest {

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

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        AppUser user = new AppUser();
        user.setUsername("testuser");
        user.setPassword("not-a-real-password-hash");
        user.setRole(Role.USER);
        userRepository.saveAndFlush(user);
    }
    @Test
    void shouldCreateTask() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Learn Integration Testing",
                              "description": "Test complete application",
                              "priority": 5
                            }
                            """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("TODO"));

        assertEquals(1, taskRepository.count());
    }
    @Test
    void shouldCreateAndRetrieveTask() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Learn Testcontainers",
                              "description": "Use PostgreSQL in tests",
                              "priority": 4
                            }
                            """)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/tasks")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(
                        jsonPath("$.items[0].title")
                                .value("Learn Testcontainers")
                )
                .andExpect(
                        jsonPath("$.items[0].status")
                                .value("TODO")
                )
                .andExpect(
                        jsonPath("$.items[0].priority")
                                .value(4)
                );
    }
    @Test
    void shouldRejectInvalidTask() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "",
                              "description": "Invalid task",
                              "priority": 10
                            }
                            """)
                )
                .andExpect(status().isBadRequest());

        assertEquals(
                0,
                taskRepository.count()
        );
    }
    @Test
    void shouldReturn404WhenTaskDoesNotExist()
            throws Exception {

        mockMvc.perform(
                        get("/api/v1/tasks/999")
                )
                .andExpect(status().isNotFound());
    }
    @Test
    void shouldUpdateTaskStatus() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Finish Spring Testing",
                              "description": "Complete Stage 5",
                              "priority": 5
                            }
                            """)
                )
                .andExpect(status().isCreated());

        Long id = taskRepository
                .findAll()
                .getFirst()
                .getId();

        mockMvc.perform(
                        patch("/api/v1/tasks/{id}/status", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                        {
                          "status": "COMPLETED"
                        }
                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status")
                                .value("COMPLETED")
                );
        var task =
                taskRepository
                        .findById(id)
                        .orElseThrow();

        assertEquals(
                "COMPLETED",
                task.getStatus().name()
        );
    }

    @Test
    void shouldPersistUpdatedTaskPriority() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Original task","description":"Before","priority":2}
                                """))
                .andExpect(status().isCreated());

        Long id = taskRepository.findAll().getFirst().getId();
        mockMvc.perform(put("/api/v1/tasks/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Updated task","description":"After",
                                 "status":"IN_PROGRESS","priority":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(5));

        var persisted = taskRepository.findById(id).orElseThrow();
        assertEquals(5, persisted.getPriority());
        assertEquals("Updated task", persisted.getTitle());
        assertEquals("IN_PROGRESS", persisted.getStatus().name());
    }
    @Test
    void shouldDeleteTask() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Delete this task",
                              "description": "Testing DELETE",
                              "priority": 2
                            }
                            """)
                )
                .andExpect(status().isCreated());

        Long id = taskRepository
                .findAll()
                .getFirst()
                .getId();

        mockMvc.perform(
                        delete("/api/v1/tasks/{id}", id)
                )
                .andExpect(status().isNoContent());

        assertFalse(
                taskRepository.existsById(id)
        );
    }
    @Test
    void shouldSearchTasksByTitle() throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Learn Spring Boot",
                              "description": "Backend",
                              "priority": 5
                            }
                            """)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Learn Docker",
                              "description": "Containers",
                              "priority": 4
                            }
                            """)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/tasks/search")
                                .param("title", "SPRING")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(
                        jsonPath("$[0].title")
                                .value("Learn Spring Boot")
                );
    }
    private RequestPostProcessor userJwt() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER")
        );
    }
}
