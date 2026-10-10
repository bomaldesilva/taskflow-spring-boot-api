package com.example.taskflow.integration;

import com.example.taskflow.repository.TaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TaskApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
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
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(
                        jsonPath("$[0].title")
                                .value("Learn Testcontainers")
                )
                .andExpect(
                        jsonPath("$[0].status")
                                .value("TODO")
                )
                .andExpect(
                        jsonPath("$[0].priority")
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
                                .param(
                                        "status",
                                        "COMPLETED"
                                )
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
}