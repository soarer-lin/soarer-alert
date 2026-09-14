package org.example.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisWebSocketAuthInterceptorTest {

    private final DiagnosisWebSocketAuthInterceptor interceptor = new DiagnosisWebSocketAuthInterceptor();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAuthenticatedSessionDuringHandshake() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("ops", null, List.of())
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/diagnosis");
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        HashMap<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("authenticated", Boolean.TRUE);
    }

    @Test
    void rejectsAnonymousHandshake() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/diagnosis");
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

        boolean accepted = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
