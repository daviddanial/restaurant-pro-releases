package com.telerad.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    @GetMapping("/me")
    public AuthResponse me() {
        UserPrincipal p = UserPrincipal.current();
        return new AuthResponse(null, p.getId(), p.getEmail(), p.getFullName(), p.getRole(),
                p.getTenantId(), p.getTenantCode(), null);
    }
}
