package com.example.taskflow.services;

import com.example.taskflow.model.AppUser;
import com.example.taskflow.model.Role;
import com.example.taskflow.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    private CurrentUserService currentUserService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        currentUserService = new CurrentUserService(userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "ADMIN"})
    void shouldResolveExistingUserWithSupportedRole(Role role) {
        AppUser user = user("existing-user", role);
        when(userRepository.findByUsername("existing-user")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                authentication("existing-user", "ROLE_" + role.name()));

        assertSame(user, currentUserService.requireUser());

        verify(userRepository).findByUsername("existing-user");
    }

    @Test
    void shouldResolveJwtSubjectAsImmutableUserId() {
        AppUser user = user("jwt-owner", Role.USER);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication("42", 0L, "ROLE_USER"));

        assertSame(user, currentUserService.requireUser());

        verify(userRepository).findById(42L);
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void shouldAcceptTheBearerFactorAddedBySpringSecurityAlongsideTheCurrentRole() {
        AppUser user = user("jwt-owner", Role.USER);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        JwtAuthenticationToken authentication = convertedJwt("USER");
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("FACTOR_BEARER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertSame(user, currentUserService.requireUser());
    }

    @Test
    void shouldStillRejectMultipleRoleClaimsWhenBearerFactorIsPresent() {
        AppUser user = user("jwt-owner", Role.USER);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(convertedJwt("USER", "ADMIN"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
    }

    @Test
    void shouldRejectJwtForADeletedUserId() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication("42", 0L, "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
        verify(userRepository).findById(42L);
    }

    @Test
    void shouldRejectLegacyJwtUsernameSubjectsWithoutFallingBackToUsernameLookup() {
        SecurityContextHolder.getContext().setAuthentication(
                jwtAuthentication("jwt-owner", 0L, "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectARevokedTokenVersion() {
        AppUser user = user("jwt-owner", Role.USER);
        user.setTokenVersion(1L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication("42", 0L, "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
    }

    @Test
    void shouldRejectJwtAuthoritiesThatDisagreeWithTheCurrentDatabaseRole() {
        AppUser user = user("jwt-owner", Role.ADMIN);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication("42", 0L, "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
    }

    @Test
    void shouldRejectDisabledUsersForNonJwtAuthentication() {
        AppUser user = user("disabled-user", Role.USER);
        user.setEnabled(false);
        when(userRepository.findByUsername("disabled-user")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(authentication("disabled-user", "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
    }

    @Test
    void shouldCheckCurrentDatabaseRolesForNonJwtAuthentication() {
        AppUser user = user("existing-user", Role.ADMIN);
        when(userRepository.findByUsername("existing-user")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(authentication("existing-user", "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
    }

    @Test
    void shouldRejectMissingAuthentication() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectAnonymousAuthenticationEvenWithSupportedAuthority() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectUnauthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("existing-user", "unused"));

        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_VIEWER", "USER", "SCOPE_tasks"})
    void shouldRejectUnsupportedAuthorities(String authority) {
        SecurityContextHolder.getContext().setAuthentication(authentication("existing-user", authority));

        assertThrows(AccessDeniedException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectAuthenticatedPrincipalWithoutAuthorities() {
        SecurityContextHolder.getContext().setAuthentication(authentication("existing-user"));

        assertThrows(AccessDeniedException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void shouldRejectMissingOrBlankUsername(String username) {
        SecurityContextHolder.getContext().setAuthentication(authentication(username, "ROLE_USER"));

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectAuthenticatedUserMissingFromDatabase() {
        SecurityContextHolder.getContext().setAuthentication(authentication("deleted-user", "ROLE_USER"));
        when(userRepository.findByUsername("deleted-user")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, currentUserService::requireUser);

        verify(userRepository).findByUsername("deleted-user");
    }

    private static AppUser user(String username, Role role) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private static JwtAuthenticationToken jwtAuthentication(String subject, long version, String authority) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(subject)
                .claim("ver", version)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)));
    }

    private static JwtAuthenticationToken convertedJwt(String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("42")
                .claim("ver", 0L)
                .claim("roles", List.of(roles))
                .build();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return (JwtAuthenticationToken) converter.convert(jwt);
    }

    private static UsernamePasswordAuthenticationToken authentication(String username, String... authorities) {
        return new UsernamePasswordAuthenticationToken(username, "unused",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()) {
            @Override
            public String getName() {
                return username;
            }
        };
    }
}
