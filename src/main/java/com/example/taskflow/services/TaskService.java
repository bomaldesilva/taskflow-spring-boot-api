package com.example.taskflow.services;

import com.example.taskflow.exception.TaskNotFoundException;
import com.example.taskflow.dto.request.TaskListRequest;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.repository.TaskRepository;
import com.example.taskflow.repository.TaskSpecifications;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;
    private final CurrentUserService currentUserService;

    public TaskService(
            TaskRepository taskRepository,
            MeterRegistry meterRegistry,
            CurrentUserService currentUserService) {

        this.taskRepository = taskRepository;
        this.currentUserService = currentUserService;

        this.taskCreatedCounter =
                Counter.builder("taskflow.tasks.created")
                        .description(
                                "Number of tasks created"
                        )
                        .register(meterRegistry);
    }

    public Page<Task> getTasks(TaskListRequest query) {
        AppUser user = currentUserService.requireUser();
        return taskRepository.findAll(
                TaskSpecifications.matching(user.getId(), query.status(), query.priority(), query.title()),
                query.pageable());
    }

    private final Counter taskCreatedCounter;

    public Task getTaskById(Long id) {
        AppUser user = currentUserService.requireUser();

        log.debug(
                "Finding task with id: {}",
                id
        );

        return taskRepository
                .findByIdAndOwnerId(id, user.getId())
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

    AppUser user = currentUserService.requireUser();

    log.info(
            "Creating task with title: {}",
            title
    );

    Task task = new Task();

    task.setTitle(title);
    task.setDescription(description);
    task.setStatus(TaskStatus.TODO);
    task.setPriority(priority);
    task.setOwner(user);

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
        task.setPriority(priority);
        return taskRepository.save(task);
    }
    @Transactional
    public Task deleteTask(Long id) {
        Task task = getTaskById(id);
        taskRepository.delete(task);
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
        AppUser user = currentUserService.requireUser();
        Objects.requireNonNull(status, "Status is required.");
        return taskRepository.findAll(TaskSpecifications.matching(user.getId(), status, null, null));
    }
    public List<Task> searchTasks(
            String title) {
        AppUser user = currentUserService.requireUser();
        return taskRepository.findAll(TaskSpecifications.matchingLegacyTitle(user.getId(), title));
    }

}
