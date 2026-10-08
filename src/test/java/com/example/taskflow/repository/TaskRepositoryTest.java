package com.example.taskflow.repository;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TaskRepositoryTest {

    @Autowired
    private TaskRepository taskRepository;
    @Test
    void shouldSaveTask() {

        Task task = new Task();

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
        task1.setTitle("Learn Spring");
        task1.setDescription("Spring Boot");
        task1.setStatus(TaskStatus.TODO);
        task1.setPriority(5);

        Task task2 = new Task();
        task2.setTitle("Learn Docker");
        task2.setDescription("Containers");
        task2.setStatus(TaskStatus.COMPLETED);
        task2.setPriority(4);

        Task task3 = new Task();
        task3.setTitle("Learn Testing");
        task3.setDescription("JUnit");
        task3.setStatus(TaskStatus.TODO);
        task3.setPriority(3);

        taskRepository.save(task1);
        taskRepository.save(task2);
        taskRepository.save(task3);

        List<Task> todoTasks =
                taskRepository.findByStatus(TaskStatus.TODO);

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
        task1.setTitle("Learn Spring Boot");
        task1.setDescription("Backend development");
        task1.setStatus(TaskStatus.TODO);
        task1.setPriority(5);

        Task task2 = new Task();
        task2.setTitle("Learn Docker");
        task2.setDescription("Containers");
        task2.setStatus(TaskStatus.TODO);
        task2.setPriority(4);

        taskRepository.save(task1);
        taskRepository.save(task2);

        List<Task> result =
                taskRepository
                        .findByTitleContainingIgnoreCase("SPRING");

        assertEquals(1, result.size());

        assertEquals(
                "Learn Spring Boot",
                result.getFirst().getTitle()
        );
    }
    @Test
    void shouldFindTaskById() {

        Task task = new Task();

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
}