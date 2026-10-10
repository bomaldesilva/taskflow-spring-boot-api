package com.example.taskflow.dto.response;

import com.example.taskflow.model.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record TaskResponse(
        @Schema(
                description = "Unique task ID",
                example = "1"
        )
        Long id,

        @Schema(
                example = "Learn Swagger"
        )
        String title,

        @Schema(
                example = "Complete Stage 11"
        )
        String description,

        @Schema(
                example = "TODO"
        )
        TaskStatus status,

        @Schema(
                example = "5"
        )
        Integer priority
) {
}