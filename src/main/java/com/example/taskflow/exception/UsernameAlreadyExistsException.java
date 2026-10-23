package com.example.taskflow.exception;

public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException() {
        super("Username already exists");
    }

    public UsernameAlreadyExistsException(Throwable cause) {
        super("Username already exists", cause);
    }
}
