package com.example.taskflow.services;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.repository.TaskRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(
            TaskRepository taskRepository,
            MeterRegistry meterRegistry) {

        this.taskRepository = taskRepository;

        this.taskCreatedCounter =
                Counter.builder("taskflow.tasks.created")
                        .description(
                                "Number of tasks created"
                        )
                        .register(meterRegistry);
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    private final Counter taskCreatedCounter;

    public Task getTaskById(Long id) {

        log.debug(
                "Finding task with id: {}",
                id
        );

        return taskRepository
                .findById(id)
                .orElseThrow(() -> {

                    log.warn(
                            "Task not found with id: {}",
                            id
                    );

                    return new TaskNotFoundException(id);
                });
    }


        private static final Logger log =
                LoggerFactory.getLogger(TaskService.class);

//    public Task createTask(
//            String title,
//            String description, @Min(1) @Max(5) Integer priority) {
//
//        Task task = new Task();
//
//        task.setTitle(title);
//        task.setDescription(description);
//        task.setStatus(TaskStatus.TODO);
//        task.setPriority(priority);
//        return taskRepository.save(task);
//    }
    //log added
@Transactional
public Task createTask(
        String title,
        String description,
        Integer priority) {

    log.info(
            "Creating task with title: {}",
            title
    );

    Task task = new Task();

    task.setTitle(title);
    task.setDescription(description);
    task.setStatus(TaskStatus.TODO);
    task.setPriority(priority);

    Task savedTask = taskRepository.save(task);

    log.info(
            "Task created successfully with id: {}",
            savedTask.getId()
    );

    taskCreatedCounter.increment();

    return savedTask;
}
    @Transactional
    public Task updateTask(
            Long id,
            String title,
            String description,
            TaskStatus status,
            @Min(1) @Max(5) Integer priority) {

        Task task = getTaskById(id);

        task.setTitle(title);
        task.setDescription(description);
        task.setStatus(status);
        return taskRepository.save(task);
    }
    @Transactional
    public Task deleteTask(Long id) {

        if (!taskRepository.existsById(id)) {
            throw new TaskNotFoundException(id);
        }

        taskRepository.deleteById(id);
        return null;
    }
    @Transactional
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