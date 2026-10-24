package com.example.taskflow.integration;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.dto.request.TaskListRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.taskflow.repository.TaskSpecifications.*;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TaskRepositoryPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private UserRepository userRepository;

    private AppUser owner;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        owner = new AppUser();
        owner.setUsername("repository-user");
        owner.setPassword("test-only-password-hash");
        owner.setRole(Role.USER);
        owner = userRepository.saveAndFlush(owner);
    }
    @Test
    void shouldSaveTaskUsingPostgres() {

        Task task = new Task();
        task.setOwner(owner);

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
        todoTask.setOwner(owner);

        todoTask.setTitle("Learn Docker");
        todoTask.setDescription("Practice containers");
        todoTask.setStatus(TaskStatus.TODO);
        todoTask.setPriority(5);

        Task completedTask = new Task();
        completedTask.setOwner(owner);

        completedTask.setTitle("Learn JPA");
        completedTask.setDescription("Completed");
        completedTask.setStatus(TaskStatus.COMPLETED);
        completedTask.setPriority(3);

        taskRepository.save(todoTask);
        taskRepository.save(completedTask);

        List<Task> result =
                taskRepository.findAll(ownedBy(owner.getId()).and(withStatus(TaskStatus.TODO)));

        assertEquals(1, result.size());

        assertEquals(
                "Learn Docker",
                result.getFirst().getTitle()
        );
    }
    @Test
    void shouldSearchTitleIgnoringCaseUsingPostgres() {

        Task task = new Task();
        task.setOwner(owner);

        task.setTitle("Master Spring Boot");
        task.setDescription("Backend development");
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setPriority(5);

        taskRepository.save(task);

        List<Task> result =
                taskRepository
                        .findAll(ownedBy(owner.getId()).and(withTitleContaining("SPRING")));

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

    @Test
    void shouldCombineOwnerAndOptionalFiltersBeforePagingAndCounting() {
        Task first = saveTask(owner, "Spring Alpha", TaskStatus.TODO, 5);
        Task second = saveTask(owner, "SPRING Beta", TaskStatus.TODO, 5);
        saveTask(owner, "Spring completed", TaskStatus.COMPLETED, 5);
        saveTask(owner, "Spring low priority", TaskStatus.TODO, 2);
        saveTask(owner, "Docker task", TaskStatus.TODO, 5);

        AppUser other = new AppUser();
        other.setUsername("other-repository-user");
        other.setPassword("test-only-password-hash");
        other.setRole(Role.USER);
        other = userRepository.saveAndFlush(other);
        saveTask(other, "Spring owned by someone else", TaskStatus.TODO, 5);

        TaskListRequest query = new TaskListRequest(
                0, 1, TaskStatus.TODO, 5, "SPRING", List.of("id,asc"));
        var specification = ownedBy(owner.getId())
                .and(withStatus(query.status()))
                .and(withPriority(query.priority()))
                .and(withTitleContaining(query.title()));

        var firstPage = taskRepository.findAll(specification, query.pageable());
        assertEquals(List.of(first.getId()), firstPage.getContent().stream().map(Task::getId).toList());
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());

        var lastPage = taskRepository.findAll(specification, query.pageable().next());
        assertEquals(List.of(second.getId()), lastPage.getContent().stream().map(Task::getId).toList());
        assertEquals(2, lastPage.getTotalElements());

        var noMatches = taskRepository.findAll(ownedBy(owner.getId())
                .and(withStatus(TaskStatus.TODO))
                .and(withPriority(1))
                .and(withTitleContaining(query.title())), query.pageable());
        assertTrue(noMatches.isEmpty());
        assertEquals(0, noMatches.getTotalElements());
        assertEquals(0, noMatches.getTotalPages());
    }

    private Task saveTask(AppUser taskOwner, String title, TaskStatus status, int priority) {
        Task task = new Task();
        task.setOwner(taskOwner);
        task.setTitle(title);
        task.setStatus(status);
        task.setPriority(priority);
        return taskRepository.saveAndFlush(task);
    }

}
