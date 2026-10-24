package com.example.taskflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A single-instance, in-memory fixed window shared by login and registration
 * attempts from the same remote address. Forwarded headers are not consulted.
 * Distributed deployments need a shared limiter or an enforcing gateway.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/register");
    private static final String TOO_MANY_REQUESTS = """
            {"type":"about:blank","title":"Too Many Requests","status":429,
             "detail":"Too many authentication attempts. Try again after the retry interval."}
            """;

    private final AuthRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public AuthRateLimitFilter(AuthRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !"POST".equals(request.getMethod())
                || !AUTH_PATHS.contains(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long retryAfter = reserveRequest(request.getRemoteAddr());
        if (retryAfter == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(TOO_MANY_REQUESTS);
    }

    private synchronized long reserveRequest(String client) {
        Instant now = clock.instant();
        Window current = windows.get(client);
        if (current != null && !current.expiresAt().isAfter(now)) {
            windows.remove(client);
            current = null;
        }
        if (current != null) {
            if (current.requests() >= properties.getMaxRequests()) {
                return retryAfter(now, current.expiresAt());
            }
            windows.put(client, new Window(current.expiresAt(), current.requests() + 1));
            return 0;
        }

        if (windows.size() >= properties.getMaxClients()) {
            windows.values().removeIf(window -> !window.expiresAt().isAfter(now));
            if (windows.size() >= properties.getMaxClients()) {
                // Keep live client counters intact rather than evicting them to admit new keys.
                Instant earliestExpiry = windows.values().stream()
                        .map(Window::expiresAt).min(Instant::compareTo).orElseThrow();
                return retryAfter(now, earliestExpiry);
            }
        }
        windows.put(client, new Window(now.plusSeconds(properties.getWindowSeconds()), 1));
        return 0;
    }

    private static long retryAfter(Instant now, Instant expiry) {
        Duration remaining = Duration.between(now, expiry);
        return Math.max(1, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
    }

    private record Window(Instant expiresAt, int requests) {
    }
}
