package com.example.taskflow.controller;

import com.example.taskflow.dto.request.CreateTaskRequest;
import com.example.taskflow.dto.request.UpdateTaskRequest;
import com.example.taskflow.dto.request.UpdateTaskStatusRequest;
import com.example.taskflow.dto.response.TaskResponse;
import com.example.taskflow.mappers.TaskMapper;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.services.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.net.URI;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(
        name = "Tasks",
        description = "Task management operations"
)
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;
    public TaskController(
            TaskService taskService,
            TaskMapper taskMapper) {

        this.taskService = taskService;
        this.taskMapper = taskMapper;
    }
    @Operation(
            summary = "Create a task",
            description = "Creates a new task with TODO status"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Task created"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid task data"
            )
    })
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
    @Operation(
            summary = "Get all tasks",
            description = "Returns all tasks stored in TaskFlow"
    )
    @GetMapping
    public List<TaskResponse> getAllTasks() {
        return taskService
                .getAllTasks()
                .stream()
                .map(taskMapper::toResponse)
                .toList();
    }
    @Operation(
            summary = "Get task by ID",
            description = "Returns a task using its unique ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Task found"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Task not found"
            )
    })
    @GetMapping("/{id}")
    public TaskResponse getTaskById(
            @Parameter(
                    description = "Unique ID of the task",
                    example = "1"
            )
            @PathVariable Long id) {
        Task task = taskService.getTaskById(id);
        return taskMapper.toResponse(task);
    }
    @Operation(
            summary = "Update a task",
            description = "Replaces the editable data of an existing task"
    )
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
    @Operation(
            summary = "Delete a task",
            description = "Deletes a task using its ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Task deleted"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Task not found"
            )
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long id) {

        taskService.deleteTask(id);

        return ResponseEntity
                .noContent()
                .build();
    }
    @Operation(
            summary = "Update task status",
            description = "Changes the status of an existing task"
    )
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
    @Operation(
            summary = "Get tasks by status",
            description = "Returns tasks having the specified status"
    )
    @GetMapping("/status/{status}")
    public List<Task> getTasksByStatus(
            @PathVariable TaskStatus status) {

        return taskService.getTasksByStatus(status);
    }
    @Operation(
            summary = "Search tasks",
            description = "Searches tasks by title, ignoring case"
    )
    @GetMapping("/search")
    public List<Task> searchTasks(
            @RequestParam String title) {

        return taskService.searchTasks(title);
    }
}