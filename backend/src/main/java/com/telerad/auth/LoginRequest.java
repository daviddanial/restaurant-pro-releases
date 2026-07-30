package com.telerad.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Please enter a valid email")
        @Email(
                regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z0-9-]{2,}$",
                message = "Please enter a valid email"
        )
        String email,
        @NotBlank(message = "Password is required")
        String password
) {
    public LoginRequest {
        email = email == null ? null : email.trim();
        password = password == null ? null : password.trim();
    }
}
