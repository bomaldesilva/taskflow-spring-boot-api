package com.example.taskflow.controller;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.services.TaskService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @Test
    void shouldReturnTaskById() throws Exception {

        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Spring Boot");
        task.setDescription("Practice controller tests");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        when(taskService.getTaskById(1L))
                .thenReturn(task);

        mockMvc.perform(
                        get("/api/v1/tasks/1")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title")
                        .value("Learn Spring Boot"))
                .andExpect(jsonPath("$.status")
                        .value("TODO"))
                .andExpect(jsonPath("$.priority")
                        .value(5));

        verify(taskService).getTaskById(1L);
    }
    @Test
    void shouldCreateTask() throws Exception {

        Task savedTask = new Task();

        savedTask.setId(1L);
        savedTask.setTitle("Learn MockMvc");
        savedTask.setDescription("Test POST endpoint");
        savedTask.setStatus(TaskStatus.TODO);
        savedTask.setPriority(5);

        when(taskService.createTask(
                eq("Learn MockMvc"),
                eq("Test POST endpoint"),
                eq(5)
        )).thenReturn(savedTask);

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Learn MockMvc",
                              "description": "Test POST endpoint",
                              "priority": 5
                            }
                            """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title")
                        .value("Learn MockMvc"))
                .andExpect(jsonPath("$.status")
                        .value("TODO"))
                .andExpect(jsonPath("$.priority")
                        .value(5));

        verify(taskService).createTask(
                "Learn MockMvc",
                "Test POST endpoint",
                5
        );
    }
    @Test
    void shouldReturnBadRequestWhenTitleIsBlank()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "",
                              "description": "Invalid task",
                              "priority": 5
                            }
                            """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskService);
    }
    @Test
    void shouldReturnBadRequestWhenPriorityIsInvalid()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "title": "Invalid priority",
                              "description": "Priority too high",
                              "priority": 10
                            }
                            """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskService);
    }
    @Test
    void shouldReturnNotFoundWhenTaskDoesNotExist()
            throws Exception {

        when(taskService.getTaskById(999L))
                .thenThrow(
                        new TaskNotFoundException(999L)
                );

        mockMvc.perform(
                        get("/api/v1/tasks/999")
                )
                .andExpect(status().isNotFound());

        verify(taskService).getTaskById(999L);
    }
    @Test
    void shouldUpdateTaskStatus() throws Exception {

        Task updatedTask = new Task();

        updatedTask.setId(1L);
        updatedTask.setTitle("Learn Testing");
        updatedTask.setStatus(TaskStatus.COMPLETED);
        updatedTask.setPriority(5);

        when(taskService.updateStatus(
                1L,
                TaskStatus.COMPLETED
        )).thenReturn(updatedTask);

        mockMvc.perform(
                        patch("/api/v1/tasks/1/status")
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

        verify(taskService).updateStatus(
                1L,
                TaskStatus.COMPLETED
        );
    }
}