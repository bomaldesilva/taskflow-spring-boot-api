package com.example.taskflow.dto.request;

import com.example.taskflow.model.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusRequest(
        @Schema(
                description = "New task status",
                example = "COMPLETED"
        )
        @NotNull(message = "Status is required")
        TaskStatus status

) {
}