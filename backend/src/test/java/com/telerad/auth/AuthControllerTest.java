package com.telerad.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock AuthService authService;
    @Mock HttpServletRequest httpServletRequest;
    @InjectMocks AuthController authController;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void login_delegatesToService() {
        LoginRequest req = new LoginRequest("a@b.co", "pw");
        AuthResponse expected = new AuthResponse("tok", UUID.randomUUID(), "a@b.co", "A",
                com.telerad.common.Role.ADMIN, null, null, null);
        when(httpServletRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        when(authService.login(req, "10.0.0.1")).thenReturn(expected);

        AuthResponse actual = authController.login(req, httpServletRequest);
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void me_returnsCurrentPrincipal() {
        UUID id = UUID.randomUUID();
        UserPrincipal p = new UserPrincipal(id, "rad@telerad.local", "Dr", com.telerad.common.Role.RADIOLOGIST, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));

        AuthResponse me = authController.me();
        assertThat(me.accessToken()).isNull();
        assertThat(me.userId()).isEqualTo(id);
        assertThat(me.email()).isEqualTo("rad@telerad.local");
        assertThat(me.role()).isEqualTo(com.telerad.common.Role.RADIOLOGIST);
    }
}
