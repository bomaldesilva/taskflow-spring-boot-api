package com.example.taskflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(
            TaskNotFoundException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND,
                        exception.getMessage()
                );

        problem.setTitle("Task not found");

        return problem;
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception) {

        Map<String, String> errors =
                exception
                        .getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        error -> error
                                                .getDefaultMessage(),
                                        (first, second) -> first
                                )
                        );

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.BAD_REQUEST
                );

        problem.setTitle("Validation failed");

        problem.setDetail(
                "One or more request fields are invalid."
        );

        problem.setProperty(
                "errors",
                errors
        );

        return problem;
    }
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(
            Exception exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR
                );

        problem.setTitle("Internal server error");

        problem.setDetail(
                "An unexpected error occurred."
        );

        return problem;
    }
}