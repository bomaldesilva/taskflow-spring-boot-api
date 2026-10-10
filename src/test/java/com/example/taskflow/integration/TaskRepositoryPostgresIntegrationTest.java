package com.example.taskflow.integration;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class TaskRepositoryPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }
    @Test
    void shouldSaveTaskUsingPostgres() {

        Task task = new Task();

        task.setTitle("Learn Testcontainers");
        task.setDescription("Use real PostgreSQL");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        Task savedTask =
                taskRepository.save(task);

        assertNotNull(savedTask.getId());

        Task foundTask =
                taskRepository
                        .findById(savedTask.getId())
                        .orElseThrow();

        assertEquals(
                "Learn Testcontainers",
                foundTask.getTitle()
        );

        assertEquals(
                TaskStatus.TODO,
                foundTask.getStatus()
        );

        assertEquals(
                5,
                foundTask.getPriority()
        );
    }
    @Test
    void shouldFindTodoTasksUsingPostgres() {

        Task todoTask = new Task();

        todoTask.setTitle("Learn Docker");
        todoTask.setDescription("Practice containers");
        todoTask.setStatus(TaskStatus.TODO);
        todoTask.setPriority(5);

        Task completedTask = new Task();

        completedTask.setTitle("Learn JPA");
        completedTask.setDescription("Completed");
        completedTask.setStatus(TaskStatus.COMPLETED);
        completedTask.setPriority(3);

        taskRepository.save(todoTask);
        taskRepository.save(completedTask);

        List<Task> result =
                taskRepository.findByStatus(
                        TaskStatus.TODO
                );

        assertEquals(1, result.size());

        assertEquals(
                "Learn Docker",
                result.getFirst().getTitle()
        );
    }
    @Test
    void shouldSearchTitleIgnoringCaseUsingPostgres() {

        Task task = new Task();

        task.setTitle("Master Spring Boot");
        task.setDescription("Backend development");
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setPriority(5);

        taskRepository.save(task);

        List<Task> result =
                taskRepository
                        .findByTitleContainingIgnoreCase(
                                "SPRING"
                        );

        assertEquals(1, result.size());

        assertEquals(
                "Master Spring Boot",
                result.getFirst().getTitle()
        );
    }
    @Test
    void shouldRunFlywayMigrations() {

        Integer migrationCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM flyway_schema_history
                        WHERE success = true
                        """,
                        Integer.class
                );

        assertNotNull(migrationCount);

        assertTrue(migrationCount >= 1);
    }

}