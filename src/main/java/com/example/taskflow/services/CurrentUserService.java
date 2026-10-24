package com.example.taskflow.services;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.security.JwtTokenIdentity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        boolean allowedRole = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        if (!allowedRole) {
            throw new AccessDeniedException("A task role is required.");
        }
        AppUser user;
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            var identity = JwtTokenIdentity.from(jwtAuthentication.getToken())
                    .orElseThrow(() -> unavailable());
            user = userRepository.findById(identity.userId()).orElseThrow(() -> unavailable());
            Set<String> roles = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .collect(Collectors.toSet());
            if (user.getTokenVersion() != identity.version()
                    || !roles.equals(Set.of("ROLE_" + user.getRole().name()))) {
                throw unavailable();
            }
        } else {
            // Test or trusted in-process user principals retain username lookup.
            // Bearer JWTs never fall back to usernames after the subject migration.
            String username = authentication.getName();
            if (username == null || username.isBlank()) {
                throw unavailable();
            }
            user = userRepository.findByUsername(username).orElseThrow(() -> unavailable());
            if (authentication.getAuthorities().stream()
                    .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + user.getRole().name()))) {
                throw unavailable();
            }
        }
        if (!user.isEnabled()) {
            throw unavailable();
        }
        return user;
    }

    private BadCredentialsException unavailable() {
        return new BadCredentialsException("The authenticated user is unavailable.");
    }
}
