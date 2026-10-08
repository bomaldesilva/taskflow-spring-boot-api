package com.example.taskflow.dto;

import com.example.taskflow.model.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTaskRequest(

        @NotBlank
        @Size(max = 100)
        String title,

        @Size(max = 1000)
        String description,

        TaskStatus status

) {
}