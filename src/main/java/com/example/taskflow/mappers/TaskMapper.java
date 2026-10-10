package com.example.taskflow.mappers;

import com.example.taskflow.dto.response.TaskResponse;
import com.example.taskflow.model.Task;

import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public TaskResponse toResponse(Task task) {

        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority()
        );
    }
}