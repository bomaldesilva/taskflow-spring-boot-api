package com.example.taskflow.services;

import com.example.taskflow.dto.auth.*;
import com.example.taskflow.exception.UsernameAlreadyExistsException;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.UserRepository;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            TokenService tokenService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager =
                authenticationManager;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(
            RegisterRequest request) {

        if (userRepository.existsByUsername(
                request.username())) {

            throw new UsernameAlreadyExistsException();
        }

        AppUser user = new AppUser();

        user.setUsername(request.username());

        user.setPassword(
                passwordEncoder.encode(
                        request.password()
                )
        );

        user.setRole(Role.USER);

        AppUser saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameConflict(exception)) {
                throw new UsernameAlreadyExistsException(exception);
            }
            throw exception;
        }

        return tokenResponse(saved);
    }

    public AuthResponse login(
            LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),
                        request.password()
                )
        );

        AppUser user =
                userRepository
                        .findByUsername(
                                request.username()
                        )
                        .orElseThrow();

        return tokenResponse(user);
    }

    private AuthResponse tokenResponse(AppUser user) {
        return new AuthResponse(
                tokenService.generateToken(user),
                "Bearer",
                tokenService.getExpirationSeconds()
        );
    }

    private boolean isUsernameConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "app_users_username_key".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
