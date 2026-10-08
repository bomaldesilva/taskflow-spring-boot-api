package com.example.taskflow.repository;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository
        extends JpaRepository<Task, Long> {
    List<Task> findByStatus(TaskStatus status);
    List<Task> findByTitleContainingIgnoreCase(
            String title
    );
}