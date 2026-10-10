package com.example.taskflow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @Schema(
                description = "Task title",
                example = "Learn Swagger"
        )
        @NotBlank(message = "Title is required")
        @Size(
                max = 100,
                message = "Title cannot exceed 100 characters"
        )
        String title,

        @Size(
                max = 1000,
                message = "Description cannot exceed 1000 characters"
        )
        @Schema(
                description = "Detailed task description",
                example = "Document the TaskFlow REST API"
        )
        @Size(max = 1000)
        String description,
        @Schema(
                description = "Task priority from 1 to 5",
                example = "5",
                minimum = "1",
                maximum = "5"
        )
        @Min(
                value = 1,
                message = "Priority must be at least 1"
        )
        @Max(
                value = 5,
                message = "Priority cannot exceed 5"
        )
        Integer priority

) {
}