package com.telerad.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleResponseStatusException() {
        ResponseEntity<Map<String, Object>> res = handler.handle(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).containsEntry("error", "Invalid credentials");
    }

    @Test
    void handleResponseStatusException_nullReason() {
        ResponseEntity<Map<String, Object>> res = handler.handle(
                new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThat(res.getBody()).containsEntry("error", "403 FORBIDDEN");
    }

    @Test
    void handleValidation_emailField() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "loginRequest");
        binding.addError(new FieldError("loginRequest", "email", "Please enter a valid email"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<Map<String, Object>> res = handler.handleValidation(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("error", "Please enter a valid email");
    }

    @Test
    void handleValidation_passwordField() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "loginRequest");
        binding.addError(new FieldError("loginRequest", "password", "Password is required"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<Map<String, Object>> res = handler.handleValidation(ex);
        assertThat(res.getBody()).containsEntry("error", "Password is required");
    }

    @Test
    void handleValidation_defaultMessage() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "obj");
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<Map<String, Object>> res = handler.handleValidation(ex);
        assertThat(res.getBody()).containsEntry("error", "Invalid email or password");
    }

    @Test
    void handleGeneric() {
        ResponseEntity<Map<String, Object>> res = handler.handleGeneric(new RuntimeException("boom"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).containsEntry("error", "Unexpected error");
    }

    @Test
    void wrongRoleIs403NotA500() {
        var res = handler.handleDenied(new org.springframework.security.access.AccessDeniedException("x"));
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void unknownPathIs404() {
        var res = handler.handleNotFound(new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "api/studies"));
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().value()).isEqualTo(404);
    }
}
