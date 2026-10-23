package com.example.taskflow.services;

import com.example.taskflow.dto.auth.RegisterRequest;
import com.example.taskflow.exception.UsernameAlreadyExistsException;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private TokenService tokenService;

    private AuthService authService;

    private final RegisterRequest request = new RegisterRequest("testuser", "test-password");

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager, tokenService);
    }

    @Test
    void shouldRejectAnExistingUsernameBeforeSaving() {
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        assertThrows(UsernameAlreadyExistsException.class, () -> authService.register(request));

        verify(userRepository, never()).saveAndFlush(any(AppUser.class));
        verifyNoInteractions(tokenService);
    }

    @Test
    void shouldTranslateAUsernameUniquenessRace() {
        var constraintViolation = new ConstraintViolationException(
                "Duplicate username", new SQLException("Unique violation", "23505"), "app_users_username_key");
        var databaseException = new DataIntegrityViolationException("Insert failed", constraintViolation);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenThrow(databaseException);

        var exception = assertThrows(UsernameAlreadyExistsException.class,
                () -> authService.register(request));

        assertSame(databaseException, exception.getCause());
        verifyNoInteractions(tokenService);
    }

    @Test
    void shouldPreserveUnrelatedDatabaseFailures() {
        var constraintViolation = new ConstraintViolationException(
                "Other constraint", new SQLException("Constraint violation"), "another_constraint");
        var databaseException = new DataIntegrityViolationException("Insert failed", constraintViolation);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenThrow(databaseException);

        assertSame(databaseException,
                assertThrows(DataIntegrityViolationException.class, () -> authService.register(request)));
        verifyNoInteractions(tokenService);
    }
}
