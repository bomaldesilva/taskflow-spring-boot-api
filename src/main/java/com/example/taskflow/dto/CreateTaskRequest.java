package com.example.taskflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateTaskRequest(

        @NotBlank(message = "Title is required")
        @Size(max = 100)
        String title,

        @Size(max = 1000)
        String description,
        @Min(1)
        @Max(5)
        Integer priority

) {
}