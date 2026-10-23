package com.example.taskflow.dto.auth;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn
) {
}