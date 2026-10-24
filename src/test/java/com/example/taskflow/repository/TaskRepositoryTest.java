package com.example.taskflow.repository;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.taskflow.repository.TaskSpecifications.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TaskRepositoryTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    private AppUser owner;

    @BeforeEach
    void createOwner() {
        owner = saveUser("repository-owner");
    }

    @Test
    void shouldSaveTask() {

        Task task = new Task();
        task.setOwner(owner);

        task.setTitle("Learn JPA Testing");
        task.setDescription("Practice DataJpaTest");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        Task savedTask = taskRepository.save(task);

        assertNotNull(savedTask.getId());

        assertEquals(
                "Learn JPA Testing",
                savedTask.getTitle()
        );

        assertEquals(
                TaskStatus.TODO,
                savedTask.getStatus()
        );
    }
    @Test
    void shouldFindTasksByStatus() {

        Task task1 = new Task();
        task1.setOwner(owner);
        task1.setTitle("Learn Spring");
        task1.setDescription("Spring Boot");
        task1.setStatus(TaskStatus.TODO);
        task1.setPriority(5);

        Task task2 = new Task();
        task2.setOwner(owner);
        task2.setTitle("Learn Docker");
        task2.setDescription("Containers");
        task2.setStatus(TaskStatus.COMPLETED);
        task2.setPriority(4);

        Task task3 = new Task();
        task3.setOwner(owner);
        task3.setTitle("Learn Testing");
        task3.setDescription("JUnit");
        task3.setStatus(TaskStatus.TODO);
        task3.setPriority(3);

        taskRepository.save(task1);
        taskRepository.save(task2);
        taskRepository.save(task3);
        saveTask(saveUser("other-owner"), "Other user's TODO task", TaskStatus.TODO, 5);

        List<Task> todoTasks =
                taskRepository.findAll(ownedBy(owner.getId()).and(withStatus(TaskStatus.TODO)));

        assertEquals(2, todoTasks.size());

        assertTrue(
                todoTasks.stream()
                        .allMatch(
                                task ->
                                        task.getStatus()
                                                == TaskStatus.TODO
                        )
        );
    }
    @Test
    void shouldFindTasksByTitleIgnoringCase() {

        Task task1 = new Task();
        task1.setOwner(owner);
        task1.setTitle("Learn Spring Boot");
        task1.setDescription("Backend development");
        task1.setStatus(TaskStatus.TODO);
        task1.setPriority(5);

        Task task2 = new Task();
        task2.setOwner(owner);
        task2.setTitle("Learn Docker");
        task2.setDescription("Containers");
        task2.setStatus(TaskStatus.TODO);
        task2.setPriority(4);

        taskRepository.save(task1);
        taskRepository.save(task2);
        saveTask(saveUser("other-owner"), "Other user's Spring task", TaskStatus.TODO, 5);

        List<Task> result =
                taskRepository
                        .findAll(ownedBy(owner.getId()).and(withTitleContaining("SPRING")));

        assertEquals(1, result.size());

        assertEquals(
                "Learn Spring Boot",
                result.getFirst().getTitle()
        );
    }
    @Test
    void shouldFindTaskById() {

        Task task = new Task();
        task.setOwner(owner);

        task.setTitle("Learn Hibernate");
        task.setDescription("Understand ORM");
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setPriority(4);

        Task savedTask =
                taskRepository.save(task);

        Task foundTask =
                taskRepository
                        .findById(savedTask.getId())
                        .orElseThrow();

        assertEquals(
                savedTask.getId(),
                foundTask.getId()
        );

        assertEquals(
                "Learn Hibernate",
                foundTask.getTitle()
        );
    }
    @Test
    void shouldDeleteTask() {

        Task task = new Task();
        task.setOwner(owner);

        task.setTitle("Delete Me");
        task.setDescription("Repository delete test");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(1);

        Task savedTask =
                taskRepository.save(task);

        Long id = savedTask.getId();

        taskRepository.deleteById(id);

        assertFalse(
                taskRepository.existsById(id)
        );
    }

    @Test
    void shouldPreserveOwnerScopeWhenEveryOptionalFilterIsMissing() {
        Task owned = saveTask(owner, "My task", TaskStatus.TODO, 5);
        saveTask(saveUser("other-owner"), "Other user's task", TaskStatus.TODO, 5);
        // The Hibernate-only slice can model a legacy unassigned row preserved by V5.
        saveTask(null, "Legacy task", TaskStatus.TODO, 5);

        List<Task> result = taskRepository.findAll(ownedBy(owner.getId())
                .and(withStatus(null))
                .and(withPriority(null))
                .and(withTitleContaining(null)));

        assertEquals(List.of(owned.getId()), result.stream().map(Task::getId).toList());
    }

    private AppUser saveUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword("test-only-password-hash");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
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
