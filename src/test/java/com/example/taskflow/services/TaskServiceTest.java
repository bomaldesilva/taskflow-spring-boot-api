package com.example.taskflow.services;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private CurrentUserService currentUserService;
    private AppUser currentUser;
    private MeterRegistry meterRegistry;
    private TaskService taskService;
    @BeforeEach
    void setUp() {

        meterRegistry = new SimpleMeterRegistry();
        currentUser = mock(AppUser.class);
        when(currentUserService.requireUser()).thenReturn(currentUser);

        taskService = new TaskService(
                taskRepository,
                meterRegistry,
                currentUserService
        );
    }
    @Test
    void shouldReturnTaskWhenTaskExists() {

        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Testing");
        task.setDescription("Practice JUnit");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        mockOwnedTaskLookup(1L, task);

        Task result = taskService.getTaskById(1L);

//        assertEquals(1L, result.getId());
        assertEquals("Learn Testing", result.getTitle());
//        assertEquals(TaskStatus.TODO, result.getStatus());
        verify(taskRepository).findByIdAndOwnerId(1L, 1L);
        System.out.println("end");
    }
    @Test
    void shouldThrowExceptionWhenTaskDoesNotExist() {

        mockOwnedTaskLookup(999L, null);

        assertThrows(
                TaskNotFoundException.class,
                () -> taskService.getTaskById(999L)
        );

        verify(taskRepository).findByIdAndOwnerId(999L, 1L);
    }
    @Test
    void shouldCreateTaskWithTodoStatus() {

        when(taskRepository.save(any(Task.class)))
                .thenAnswer(invocation -> {

                    Task task = invocation.getArgument(0);

                    task.setId(1L);

                    return task;
                });

        Task result = taskService.createTask(
                "Learn Mockito",
                "Practice service testing",
                5
        );

        assertNotNull(result.getId());
        assertSame(currentUser, result.getOwner());

        assertEquals(
                "Learn Mockito",
                result.getTitle()
        );

        assertEquals(
                TaskStatus.TODO,
                result.getStatus()
        );

        assertEquals(
                5,
                result.getPriority()
        );

        verify(taskRepository)
                .save(any(Task.class));
    }
    @Test
    void shouldDeleteExistingTask() {

        Task task = new Task();
        task.setId(1L);
        mockOwnedTaskLookup(1L, task);

        Task result = taskService.deleteTask(1L);

//        assertEquals(1L, result.getId());
//        assertEquals("Learn Testing", result.getTitle());
//        assertEquals(TaskStatus.TODO, result.getStatus());
        assertNull(result);
        verify(taskRepository).findByIdAndOwnerId(1L, 1L);
        verify(taskRepository).delete(task);
        System.out.println("end");
    }
    @Test
    void shouldThrowExceptionWhenDeletingMissingTask() {

        // Arrange
        mockOwnedTaskLookup(999L, null);

        // Act + Assert
        assertThrows(
                TaskNotFoundException.class,
                () -> taskService.deleteTask(999L)
        );

        // A missing or inaccessible ID must fail the owner-scoped lookup.
        verify(taskRepository).findByIdAndOwnerId(999L, 1L);

        // Delete must NOT happen
        verify(taskRepository, never()).delete(any(Task.class));
    }
    @Test
    void shouldUpdateTaskStatus() {

        // Arrange
        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Spring Boot");
        task.setDescription("Practice testing");
        task.setStatus(TaskStatus.TODO);

        mockOwnedTaskLookup(1L, task);

        when(taskRepository.save(any(Task.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Task result = taskService.updateStatus(
                1L,
                TaskStatus.COMPLETED
        );

        // Assert
        assertEquals(
                TaskStatus.COMPLETED,
                result.getStatus()
        );

        verify(taskRepository).findByIdAndOwnerId(1L, 1L);
        verify(taskRepository).save(task);
    }

    private void mockOwnedTaskLookup(Long taskId, Task task) {
        when(currentUser.getId()).thenReturn(1L);
        when(taskRepository.findByIdAndOwnerId(taskId, 1L))
                .thenReturn(Optional.ofNullable(task));
        if (task != null) {
            task.setOwner(currentUser);
        }
    }
}
