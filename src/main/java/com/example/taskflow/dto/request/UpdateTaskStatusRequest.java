package com.example.taskflow.dto.request;

import com.example.taskflow.model.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusRequest(

        @NotNull(message = "Status is required")
        TaskStatus status

) {
}