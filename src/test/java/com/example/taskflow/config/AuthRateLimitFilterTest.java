package com.example.taskflow.config;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimitFilterTest {

    private AuthRateLimitProperties properties;
    private MutableClock clock;
    private AuthRateLimitFilter filter;
    private AtomicInteger forwardedRequests;

    @BeforeEach
    void setUp() {
        properties = new AuthRateLimitProperties();
        properties.setMaxRequests(2);
        clock = new MutableClock();
        filter = new AuthRateLimitFilter(properties, clock);
        forwardedRequests = new AtomicInteger();
    }

    @Test
    void shouldShareLoginAndRegistrationLimitAndReturnProblemDetail() throws Exception {
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
        assertEquals(204, perform("POST", "/api/v1/auth/register", "192.0.2.1").getStatus());

        MockHttpServletResponse response = perform("POST", "/api/v1/auth/login", "192.0.2.1");

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())));
        Map<String, Object> problem = JsonPath.parse(response.getContentAsString()).read("$");
        assertEquals("about:blank", problem.get("type"));
        assertEquals("Too Many Requests", problem.get("title"));
        assertEquals(429, problem.get("status"));
        assertNotNull(problem.get("detail"));
        assertEquals(2, forwardedRequests.get());
    }

    @Test
    void shouldRoundRetryAfterUpAndResetAtOriginalWindowBoundary() throws Exception {
        properties.setMaxRequests(1);
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());

        clock.advance(Duration.ofMillis(1));
        assertEquals("60", perform("POST", "/api/v1/auth/login", "192.0.2.1")
                .getHeader(HttpHeaders.RETRY_AFTER));
        clock.advance(Duration.ofMillis(59_998));
        assertEquals("1", perform("POST", "/api/v1/auth/login", "192.0.2.1")
                .getHeader(HttpHeaders.RETRY_AFTER));
        clock.advance(Duration.ofMillis(1));

        assertEquals(204, perform("POST", "/api/v1/auth/register", "192.0.2.1").getStatus());
        assertEquals(2, forwardedRequests.get());
    }

    @Test
    void shouldBoundClientsWithoutEvictingLiveCountersAndReclaimExpiredWindows() throws Exception {
        properties.setMaxClients(2);
        properties.setMaxRequests(3);
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.2").getStatus());

        MockHttpServletResponse full = perform("POST", "/api/v1/auth/login", "192.0.2.3");
        assertEquals(429, full.getStatus());
        assertEquals("50", full.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());

        clock.advance(Duration.ofSeconds(50));
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.3").getStatus());
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.2").getStatus());
        MockHttpServletResponse stillBounded = perform("POST", "/api/v1/auth/login", "192.0.2.4");
        assertEquals(429, stillBounded.getStatus());
        assertEquals("10", stillBounded.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void shouldUseRemoteAddressAndIgnoreForgedForwardedHeaders() throws Exception {
        properties.setMaxRequests(1);
        MockHttpServletRequest initial = request("POST", "/api/v1/auth/login", "192.0.2.1");
        initial.addHeader("X-Forwarded-For", "198.51.100.10");
        assertEquals(204, perform(initial).getStatus());

        MockHttpServletRequest forged = request("POST", "/api/v1/auth/register", "192.0.2.1");
        forged.addHeader("X-Forwarded-For", "198.51.100.11");
        assertEquals(429, perform(forged).getStatus());

        MockHttpServletRequest otherClient = request("POST", "/api/v1/auth/login", "192.0.2.2");
        otherClient.addHeader("X-Forwarded-For", "198.51.100.10");
        assertEquals(204, perform(otherClient).getStatus());
    }

    @Test
    void shouldBypassLimitingWhenDisabledWithoutCountingRequests() throws Exception {
        properties.setEnabled(false);
        properties.setMaxRequests(1);
        properties.setMaxClients(1);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
        }
        properties.setEnabled(true);
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
        assertEquals(429, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
    }

    @ParameterizedTest
    @MethodSource("unlimitedRequests")
    void shouldBypassOtherMethodsAndEndpoints(String method, String path) throws Exception {
        properties.setMaxRequests(1);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(204, perform(method, path, "192.0.2.1").getStatus());
        }
        assertEquals(204, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
        assertEquals(429, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
    }

    @Test
    void shouldMatchAuthenticationEndpointsWithinContextPath() throws Exception {
        properties.setMaxRequests(1);
        MockHttpServletRequest login = request("POST", "/taskflow/api/v1/auth/login", "192.0.2.1");
        login.setContextPath("/taskflow");
        assertEquals(204, perform(login).getStatus());

        MockHttpServletRequest registration = request("POST", "/taskflow/api/v1/auth/register", "192.0.2.1");
        registration.setContextPath("/taskflow");
        assertEquals(429, perform(registration).getStatus());
    }

    @Test
    void shouldNotBypassLimitWithEncodedEndpointPath() throws Exception {
        properties.setMaxRequests(1);
        assertEquals(204, perform("POST", "/api/v1/auth/%6cogin", "192.0.2.1").getStatus());
        assertEquals(429, perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus());
    }

    @Test
    void shouldEnforceLimitAcrossConcurrentRequests() throws Exception {
        properties.setMaxRequests(5);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int attempt = 0; attempt < 64; attempt++) {
                attempts.add(executor.submit(
                        () -> perform("POST", "/api/v1/auth/login", "192.0.2.1").getStatus()));
            }
            int accepted = 0;
            int rejected = 0;
            for (Future<Integer> attempt : attempts) {
                int status = attempt.get(5, TimeUnit.SECONDS);
                if (status == 204) {
                    accepted++;
                } else {
                    assertEquals(429, status);
                    rejected++;
                }
            }
            assertEquals(5, accepted);
            assertEquals(59, rejected);
            assertEquals(5, forwardedRequests.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldValidatePositiveRequestWindowAndClientLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(properties).isEmpty());
            properties.setMaxRequests(0);
            properties.setWindowSeconds(0);
            properties.setMaxClients(0);

            assertEquals(Set.of("maxRequests", "windowSeconds", "maxClients"),
                    validator.validate(properties).stream()
                            .map(violation -> violation.getPropertyPath().toString())
                            .collect(Collectors.toSet()));
        }
    }

    private MockHttpServletResponse perform(String method, String path, String remoteAddress) throws Exception {
        return perform(request(method, path, remoteAddress));
    }

    private MockHttpServletResponse perform(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (forwardedRequest, forwardedResponse) -> {
            forwardedRequests.incrementAndGet();
            ((MockHttpServletResponse) forwardedResponse).setStatus(204);
        });
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static Stream<Arguments> unlimitedRequests() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/auth/login"),
                Arguments.of("PUT", "/api/v1/auth/register"),
                Arguments.of("OPTIONS", "/api/v1/auth/login"),
                Arguments.of("POST", "/api/v1/auth/logout"),
                Arguments.of("POST", "/api/v1/tasks"),
                Arguments.of("POST", "/api/v1/auth/login/"));
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
