package com.example.taskflow.repository;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository
        extends JpaRepository<Task, Long> {

    @Query("""
            select task from Task task
            where (:status is null or task.status = :status)
              and (:priority is null or task.priority = :priority)
              and (:titlePattern is null or lower(task.title) like :titlePattern escape '!')
            """)
    Page<Task> findMatchingTasks(
            @Param("status") TaskStatus status,
            @Param("priority") Integer priority,
            @Param("titlePattern") String titlePattern,
            Pageable pageable);

    List<Task> findByStatus(TaskStatus status);
    List<Task> findByTitleContainingIgnoreCase(
            String title
    );
}
