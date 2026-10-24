package com.example.taskflow.dto.request;

import com.example.taskflow.exception.InvalidTaskQueryException;
import com.example.taskflow.model.TaskStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record TaskListRequest(
        int page,
        int size,
        TaskStatus status,
        Integer priority,
        String title,
        List<String> sort
) {

    private static final Set<String> SORT_FIELDS = Set.of("id", "title", "status", "priority");

    public TaskListRequest {
        if (page < 0) {
            throw new InvalidTaskQueryException("Page must be zero or greater.");
        }
        if (size < 1 || size > 100) {
            throw new InvalidTaskQueryException("Size must be between 1 and 100.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidTaskQueryException("The requested page offset is too large. Reduce page or size.");
        }
        if (priority != null && (priority < 1 || priority > 5)) {
            throw new InvalidTaskQueryException("Priority must be between 1 and 5.");
        }
        title = title == null || title.isBlank() ? null : title.trim();
        if (title != null && title.length() > 100) {
            throw new InvalidTaskQueryException("Title filter cannot exceed 100 characters.");
        }
        sort = sort == null ? List.of("id,desc") : List.copyOf(sort);
        parseSort(sort);
    }

    public Pageable pageable() {
        return PageRequest.of(page, size, parseSort(sort));
    }

    private static Sort parseSort(List<String> parameters) {
        List<Sort.Order> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (parameters.isEmpty() || parameters.size() > SORT_FIELDS.size()) {
            throw new InvalidTaskQueryException("Supply between one and four sort fields.");
        }
        for (String parameter : parameters) {
            String[] parts = parameter.split(",", -1);
            String field = parts[0].trim();
            if (parts.length > 2 || !SORT_FIELDS.contains(field) || !seen.add(field)) {
                throw new InvalidTaskQueryException(
                        "Sort fields must be unique and one of id, title, status, priority.");
            }
            String direction = parts.length == 1 ? "asc" : parts[1].trim();
            if (!direction.equalsIgnoreCase("asc") && !direction.equalsIgnoreCase("desc")) {
                throw new InvalidTaskQueryException("Sort direction must be asc or desc.");
            }
            orders.add(new Sort.Order(Sort.Direction.fromString(direction), field));
        }
        if (!seen.contains("id")) {
            orders.add(Sort.Order.asc("id"));
        }
        return Sort.by(orders);
    }
}
