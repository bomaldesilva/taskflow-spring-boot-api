package com.example.taskflow.controller;

import com.example.taskflow.dto.CreateTaskRequest;
import com.example.taskflow.dto.UpdateTaskRequest;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.services.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<Task> createTask(
            @Valid @RequestBody CreateTaskRequest request) {

        Task task = taskService.createTask(
                request.title(),
                request.description(),
                request.priority()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(task);
    }

    @GetMapping
    public List<Task> getAllTasks() {
        return taskService.getAllTasks();
    }
    @GetMapping("/{id}")
    public Task getTaskById(
            @PathVariable Long id) {

        return taskService.getTaskById(id);
    }
    @PutMapping("/{id}")
    public Task updateTask(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskRequest request) {

        return taskService.updateTask(
                id,
                request.title(),
                request.description(),
                request.status()
        );
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long id) {

        taskService.deleteTask(id);

        return ResponseEntity.noContent().build();
    }
    @PatchMapping("/{id}/status")
    public Task updateStatus(
            @PathVariable Long id,
            @RequestParam TaskStatus status) {

        return taskService.updateStatus(id, status);
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