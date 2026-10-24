package com.example.taskflow.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid username or password.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", "You do not have permission for this operation.");
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ProblemDetail handleDuplicateUsername(UsernameAlreadyExistsException exception) {
        return problem(HttpStatus.CONFLICT, "Username already exists", "Choose a different username.");
    }

    @ExceptionHandler(InvalidTaskQueryException.class)
    public ProblemDetail handleInvalidTaskQuery(InvalidTaskQueryException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid task query", exception.getMessage());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class
    })
    public ProblemDetail handleInvalidInput(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Check the request body and parameter values.");
    }

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

        log.error("Unexpected request failure", exception);

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

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
