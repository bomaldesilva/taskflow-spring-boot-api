package com.example.taskflow.mappers;

import com.example.taskflow.dto.response.TaskResponse;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskMapperTest {

    private final TaskMapper taskMapper =
            new TaskMapper();

    @Test
    void shouldConvertTaskToTaskResponse() {

        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Architecture");
        task.setDescription("Stage 10");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        TaskResponse response =
                taskMapper.toResponse(task);

        assertEquals(1L, response.id());

        assertEquals(
                "Learn Architecture",
                response.title()
        );

        assertEquals(
                TaskStatus.TODO,
                response.status()
        );

        assertEquals(
                5,
                response.priority()
        );
    }
}
