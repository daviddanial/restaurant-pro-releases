package com.telerad.auth;

import com.telerad.common.Role;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        UUID userId,
        String email,
        String fullName,
        Role role,
        UUID tenantId,
        String tenantCode,
        String tenantName
) {}
