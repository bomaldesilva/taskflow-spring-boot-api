package com.example.taskflow.services;

import com.example.taskflow.dto.auth.*;
import com.example.taskflow.exception.UsernameAlreadyExistsException;
import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.security.AuthenticatedUser;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
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
    private final CurrentUserService currentUserService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            TokenService tokenService,
            CurrentUserService currentUserService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager =
                authenticationManager;
        this.tokenService = tokenService;
        this.currentUserService = currentUserService;
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

        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),
                        request.password()
                )
        );

        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser identity)) {
            throw new BadCredentialsException("The authenticated user is unavailable.");
        }
        AppUser user = userRepository.findById(identity.getUserId())
                .orElseThrow(() -> new BadCredentialsException("The authenticated user is unavailable."));

        return tokenResponse(user);
    }

    private AuthResponse tokenResponse(AppUser user) {
        return new AuthResponse(
                tokenService.generateToken(user),
                "Bearer",
                tokenService.getExpirationSeconds()
        );
    }

    @Transactional
    public void logout() {
        AppUser user = currentUserService.requireUser();
        if (userRepository.revokeTokens(user.getId(), user.getTokenVersion()) != 1) {
            throw new BadCredentialsException("The authenticated user is unavailable.");
        }
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
