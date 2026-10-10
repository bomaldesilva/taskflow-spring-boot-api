package com.example.taskflow.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(

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
        String description,

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