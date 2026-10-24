package com.example.taskflow.services;

import com.example.taskflow.dto.auth.RegisterRequest;
import com.example.taskflow.dto.auth.LoginRequest;
import com.example.taskflow.exception.UsernameAlreadyExistsException;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.security.AuthenticatedUser;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;

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
    @Mock
    private CurrentUserService currentUserService;

    private AuthService authService;

    private final RegisterRequest request = new RegisterRequest("testuser", "test-password");

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager, tokenService,
                currentUserService);
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

    @Test
    void shouldKeepLoginBoundToAuthenticatedIdWhenTheUsernameIsReused() {
        AppUser original = user(41L, "testuser");
        AppUser replacement = user(42L, "testuser");
        AuthenticatedUser principal = new AuthenticatedUser(original);
        when(authenticationManager.authenticate(any())).thenAnswer(invocation -> {
            // The credentials have been verified for ID 41; its name is reassigned before issuance.
            original.setUsername("renamed-original");
            return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        });
        when(userRepository.findById(41L)).thenReturn(Optional.of(original));
        // This alternate lookup deliberately remains available to expose any username fallback.
        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(replacement));
        when(tokenService.generateToken(original)).thenReturn("original-account-token");
        when(tokenService.getExpirationSeconds()).thenReturn(900L);

        var response = authService.login(new LoginRequest("testuser", "test-password"));

        assertEquals("original-account-token", response.token());
        assertEquals("Bearer", response.tokenType());
        assertEquals(900, response.expiresIn());
        verify(userRepository).findById(41L);
        verify(userRepository, never()).findByUsername(anyString());
        verify(tokenService).generateToken(original);
        verify(tokenService, never()).generateToken(replacement);
    }

    @Test
    void shouldRejectLoginIfTheAuthenticatedUserWasDeletedBeforeIssuance() {
        AuthenticatedUser principal = new AuthenticatedUser(user(41L, "testuser"));
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findById(41L)).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("testuser", "test-password")));

        verify(userRepository).findById(41L);
        verify(userRepository, never()).findByUsername(anyString());
        verifyNoInteractions(tokenService);
    }

    @Test
    void shouldRejectAnAuthenticationPrincipalWithoutPersistedAccountIdentity() {
        var unexpected = User.withUsername("testuser").password("encoded-password").roles("USER").build();
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(unexpected, null, unexpected.getAuthorities()));

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("testuser", "test-password")));

        verifyNoInteractions(userRepository, tokenService);
    }

    @Test
    void shouldPreserveAuthenticationFailureWithoutIssuingAToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Invalid credentials"));

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("testuser", "wrong-password")));

        verifyNoInteractions(userRepository, tokenService);
    }

    private static AppUser user(long id, String username) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setPassword("encoded-test-password");
        user.setRole(Role.USER);
        return user;
    }
}
