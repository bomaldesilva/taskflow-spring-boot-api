package com.example.taskflow.services;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.UserRepository;
import com.example.taskflow.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(userRepository);

    @Test
    void shouldCaptureThePersistedIdentityAlongsideCredentialAndEnabledState() {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 41L);
        user.setUsername("testuser");
        user.setPassword("encoded-test-password");
        user.setRole(Role.USER);
        user.setEnabled(false);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        AuthenticatedUser principal = assertInstanceOf(AuthenticatedUser.class,
                service.loadUserByUsername("testuser"));
        user.setUsername("renamed-after-authentication");

        assertEquals(41L, principal.getUserId());
        assertEquals("testuser", principal.getUsername());
        assertEquals("encoded-test-password", principal.getPassword());
        assertFalse(principal.isEnabled());
    }

    @Test
    void shouldRejectAUsernameMissingFromTheDatabase() {
        when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("missing-user"));
    }
}
