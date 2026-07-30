package com.telerad.auth;

import com.telerad.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String ip) {
        UserAccount user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtService.createToken(user);
        UUID tenantId = user.getTenant() != null ? user.getTenant().getId() : null;
        String tenantCode = user.getTenant() != null ? user.getTenant().getCode() : null;
        String tenantName = user.getTenant() != null ? user.getTenant().getName() : null;
        auditService.record(tenantId, user.getId(), "LOGIN", "User", user.getId().toString(), user.getEmail(), ip);
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                tenantId, tenantCode, tenantName);
    }
}
