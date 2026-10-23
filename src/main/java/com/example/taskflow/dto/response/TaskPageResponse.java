package com.example.taskflow.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record TaskPageResponse(
        List<TaskResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static TaskPageResponse from(Page<TaskResponse> result) {
        return new TaskPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}
