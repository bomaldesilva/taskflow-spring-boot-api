package com.example.taskflow.controller;

import com.example.taskflow.dto.request.CreateTaskRequest;
import com.example.taskflow.dto.request.UpdateTaskRequest;
import com.example.taskflow.dto.request.UpdateTaskStatusRequest;
import com.example.taskflow.dto.response.TaskResponse;
import com.example.taskflow.mappers.TaskMapper;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.services.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.net.URI;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;
    public TaskController(
            TaskService taskService,
            TaskMapper taskMapper) {

        this.taskService = taskService;
        this.taskMapper = taskMapper;
    }
    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @Valid @RequestBody CreateTaskRequest request) {

        Task task = taskService.createTask(
                request.title(),
                request.description(),
                request.priority()
        );
        TaskResponse response =
                taskMapper.toResponse(task);
        URI location =
                ServletUriComponentsBuilder
                        .fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(task.getId())
                        .toUri();
        return ResponseEntity
                .created(location)
                .body(response);
    }

    @GetMapping
    public List<TaskResponse> getAllTasks() {
        return taskService
                .getAllTasks()
                .stream()
                .map(taskMapper::toResponse)
                .toList();
    }
    @GetMapping("/{id}")
    public TaskResponse getTaskById(
            @PathVariable Long id) {
        Task task = taskService.getTaskById(id);
        return taskMapper.toResponse(task);
    }
    @PutMapping("/{id}")
    public TaskResponse updateTask(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskRequest request) {

        Task task =
                taskService.updateTask(
                        id,
                        request.title(),
                        request.description(),
                        request.status(),
                        request.priority()
                );
        return taskMapper.toResponse(task);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long id) {

        taskService.deleteTask(id);

        return ResponseEntity
                .noContent()
                .build();
    }
    @PatchMapping("/{id}/status")
    public TaskResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskStatusRequest request) {

        Task task = taskService.updateStatus(
                id,
                request.status()
        );

        return taskMapper.toResponse(task);
    }
    @GetMapping("/status/{status}")
    public List<Task> getTasksByStatus(
            @PathVariable TaskStatus status) {

        return taskService.getTasksByStatus(status);
    }
    @GetMapping("/search")
    public List<Task> searchTasks(
            @RequestParam String title) {

        return taskService.searchTasks(title);
    }
}