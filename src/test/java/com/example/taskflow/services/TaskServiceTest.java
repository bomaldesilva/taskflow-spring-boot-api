package com.example.taskflow.services;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
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

    @InjectMocks
    private TaskService taskService;
    @Test
    void shouldReturnTaskWhenTaskExists() {

        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Testing");
        task.setDescription("Practice JUnit");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(5);

        when(taskRepository.findById(1L))
                .thenReturn(Optional.of(task));

        Task result = taskService.getTaskById(1L);

//        assertEquals(1L, result.getId());
        assertEquals("Learn Testing", result.getTitle());
//        assertEquals(TaskStatus.TODO, result.getStatus());
        verify(taskRepository).findById(1L);
        System.out.println("end");
    }
    @Test
    void shouldThrowExceptionWhenTaskDoesNotExist() {

        when(taskRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                TaskNotFoundException.class,
                () -> taskService.getTaskById(999L)
        );

        verify(taskRepository).findById(999L);
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


        when(taskRepository.existsById(1L))
                .thenReturn(true);

        Task result = taskService.deleteTask(1L);

//        assertEquals(1L, result.getId());
//        assertEquals("Learn Testing", result.getTitle());
//        assertEquals(TaskStatus.TODO, result.getStatus());
        verify(taskRepository).deleteById(1L);
        System.out.println("end");
    }
    @Test
    void shouldThrowExceptionWhenDeletingMissingTask() {

        // Arrange
        when(taskRepository.existsById(999L))
                .thenReturn(false);

        // Act + Assert
        assertThrows(
                TaskNotFoundException.class,
                () -> taskService.deleteTask(999L)
        );

        // Repository should check existence
        verify(taskRepository).existsById(999L);

        // Delete must NOT happen
        verify(taskRepository, never()).deleteById(999L);
    }
    @Test
    void shouldUpdateTaskStatus() {

        // Arrange
        Task task = new Task();

        task.setId(1L);
        task.setTitle("Learn Spring Boot");
        task.setDescription("Practice testing");
        task.setStatus(TaskStatus.TODO);

        when(taskRepository.findById(1L))
                .thenReturn(Optional.of(task));

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

        verify(taskRepository).findById(1L);
        verify(taskRepository).save(task);
    }
}
