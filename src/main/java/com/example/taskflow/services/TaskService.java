package com.example.taskflow.services;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    public Task getTaskById(Long id) {

        return taskRepository
                .findById(id)
                .orElseThrow(
                        () -> new TaskNotFoundException(id)
                );
    }

    public Task createTask(
            String title,
            String description, @Min(1) @Max(5) Integer priority) {

        Task task = new Task();

        task.setTitle(title);
        task.setDescription(description);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(priority);
        return taskRepository.save(task);
    }
    public Task updateTask(
            Long id,
            String title,
            String description,
            TaskStatus status
            ) {

        Task task = getTaskById(id);

        task.setTitle(title);
        task.setDescription(description);
        task.setStatus(status);
        return taskRepository.save(task);
    }
    public void deleteTask(Long id) {

        if (!taskRepository.existsById(id)) {
            throw new TaskNotFoundException(id);
        }

        taskRepository.deleteById(id);
    }
    public Task updateStatus(
            Long id,
            TaskStatus status) {

        Task task = getTaskById(id);

        task.setStatus(status);

        return taskRepository.save(task);
    }
    public List<Task> getTasksByStatus(
            TaskStatus status) {

        return taskRepository.findByStatus(status);
    }
    public List<Task> searchTasks(
            String title) {

        return taskRepository
                .findByTitleContainingIgnoreCase(title);
    }
}