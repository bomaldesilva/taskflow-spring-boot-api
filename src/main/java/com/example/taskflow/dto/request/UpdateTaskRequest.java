package com.example.taskflow.dto.request;

import com.example.taskflow.model.TaskStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskRequest(

        @NotBlank(message = "Title is required")
        @Size(max = 100)
        String title,

        @Size(max = 1000)
        String description,

        @NotNull(message = "Status is required")
        TaskStatus status,

        @Min(1)
        @Max(5)
        Integer priority

) {
}