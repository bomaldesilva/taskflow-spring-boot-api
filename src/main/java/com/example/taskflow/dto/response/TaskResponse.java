package com.example.taskflow.dto.response;

import com.example.taskflow.model.TaskStatus;

public record TaskResponse(
        Long id,
        String title,
        String description,
        TaskStatus status,
        Integer priority
) {
}